(ns sched-mcp.interviewing.interview-graph
  "Interview graph construction and execution."
  (:require
   [sched-mcp.interviewing.checkpoint :as ckpt]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.interviewing.interview-nodes :as nodes]
   [sched-mcp.interviewing.lg-util :as lg]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :refer [log!]])
  (:import [org.bsc.langgraph4j StateGraph CompileConfig RunnableConfig]
           [org.bsc.langgraph4j.checkpoint MemorySaver]))

;;;-------------- Graph Construction -------------------------------------------
(def ^:diag diag (atom nil))

(def ^:diag memory-saver
  "Global MemorySaver for debugging. Enable by passing to build-interview-graph."
  (MemorySaver.))

(defn ^:diag get-checkpoint-states
  "Retrieve all checkpoint states for a given thread-id.
   Returns vector of [node-name state] tuples showing state evolution."
  [thread-id]
  (when memory-saver
    (let [config (-> (RunnableConfig/builder)
                     (.threadId thread-id)
                     (.build))
          checkpoints (try
                        (.list memory-saver config)
                        (catch Exception e
                          (log! :error (str "Error retrieving checkpoints: " (.getMessage e)))
                          []))]
      (mapv (fn [cp]
              (let [state (.state cp)
                    metadata (.metadata cp)]
                {:checkpoint-id (.checkpointId cp)
                 :parent-id (.parentCheckpointId cp)
                 :metadata (into {} metadata)
                 :state (istate/agent-state->interview-state state)}))
            checkpoints))))

(defn build-interview-graph
  "Build the interview loop graph with LLM and surrogate integration.

   Options:
   - :checkpointer - Optional MemorySaver or other checkpointer for state debugging

   Flow:
   START → check-budget → formulate → get-answer → interpret → evaluate → (conditional)
     if complete? = false → check-budget (loop back)
     if complete? = true  → END

   Note: Budget is checked BEFORE formulating questions to avoid wasting LLM calls."
  ([] (build-interview-graph {}))
  ([{:keys [checkpointer]}]
   (let [schema (-> (istate/interview-state-schema) lg/make-schema)
         factory (lg/make-state-factory
                  (fn [init-data]
                    ;; init-data is a Java Map with string keys
                    ;; Convert to Clojure map and keywordize keys
                    (let [clj-data (into {} init-data)
                          keywordized (into {} (map (fn [[k v]] [(keyword k) v]) clj-data))]
                      (istate/interview-state->agent-state
                       (istate/map->InterviewState keywordized)))))
         graph (StateGraph. schema factory)]

     ;; Add nodes
     (.addNode graph "check-budget" (lg/make-async-node nodes/check-budget))
     (.addNode graph "formulate" (lg/make-async-node nodes/formulate-question))
     (.addNode graph "get-answer" (lg/make-async-node nodes/get-answer-from-expert))
     (.addNode graph "interpret" (lg/make-async-node nodes/interpret-response))
     (.addNode graph "evaluate" (lg/make-async-node nodes/evaluate-completion))

     ;; Add unconditional edges - budget check FIRST
     (.addEdge graph StateGraph/START "check-budget")
     (.addEdge graph "check-budget" "formulate")
     (.addEdge graph "formulate" "get-answer")
     (.addEdge graph "get-answer" "interpret")
     (.addEdge graph "interpret" "evaluate")

     ;; Add conditional edge based on complete? state
     ;; Loop back to budget check, not formulate
     (.addConditionalEdges graph "evaluate"
                           (lg/make-async-edge
                            (fn [state]
                              (let [istate (istate/agent-state->interview-state state)
                                    complete? (:complete? istate)]
                                (if complete?
                                  StateGraph/END
                                  "check-budget"))))
                           (java.util.Map/of
                            StateGraph/END StateGraph/END
                            "check-budget" "check-budget"))

     ;; Compile with or without checkpointer
     ;; Set recursion limit to 30 to allow budget exhaustion before hitting limit
     (if checkpointer
       (do
         (log! :info "Building graph WITH checkpointer for debugging")
         (.compile graph (-> (CompileConfig/builder)
                             (.checkpointer checkpointer)
                             (.recursionLimit 30)
                             (.build))))
       (.compile graph (-> (CompileConfig/builder)
                           (.recursionLimit 30)
                           (.build)))))))

;;; ------------------- Execution ----------------------------------------------
(defn run-interview
  "Run an interview loop with LLM and surrogate integration.
   Returns the final InterviewState with completed ASCR.

   Persists ASCR and messages to project DB after completion.
   Optionally captures checkpoints to disk for debugging (controlled by checkpoint/enable-checkpoints? atom).

   Options:
   - :checkpointer - Optional MemorySaver for state debugging
   - :thread-id - Thread ID for checkpointing (required if checkpointer provided)
   - :file-checkpointer - Optional FileCheckpointer for persisting to disk"
  ([initial-interview-state]
   (run-interview initial-interview-state {}))
  ([initial-interview-state {:keys [checkpointer thread-id file-checkpointer]}]
   (let [graph (build-interview-graph {:checkpointer checkpointer})
         init-data (istate/interview-state->map initial-interview-state)
         {:keys [ds-id pid cid]} initial-interview-state
         file-cp (or file-checkpointer (ckpt/file-checkpointer))
         results (if (and checkpointer thread-id)
                   (let [config (-> (RunnableConfig/builder)
                                    (.threadId thread-id)
                                    (.build))]
                     (log! :info (str "Running interview with checkpointer, thread-id: " thread-id))
                     (vec (.stream graph init-data config)))
                   (vec (.stream graph init-data)))

         ;; Capture checkpoints after each step
         _ (when @ckpt/enable-checkpoints?
             (log! :info "Capturing checkpoints to disk...")
             (doseq [[idx result] (map-indexed vector results)]
               (let [agent-state (.state result)
                     istate (istate/agent-state->interview-state agent-state)
                     cp-map (ckpt/make-checkpoint
                             {:state istate
                              :project-id pid
                              :ds-id ds-id
                              :iteration idx
                              :thread-id (or thread-id "default")
                              :metadata {:step (str "iteration-" idx)}})]
                 (ckpt/save-checkpoint! file-cp cp-map))))

         final-agent-state (-> results last .state)
         final-state (istate/agent-state->interview-state final-agent-state)
         {:keys [ascr messages complete?]} final-state]

     ;; Persist ASCR and messages to project DB
     (when (and pid ds-id)
       (when-not (pdb/ASCR-exists? pid ds-id)
         (pdb/init-ASCR! pid ds-id))
       (pdb/put-ASCR! pid ds-id ascr)
       (when complete?
         (pdb/mark-ASCR-complete! pid ds-id))

       (doseq [msg messages]
         (pdb/add-msg! {:pid pid
                        :cid cid
                        :from (:from msg)
                        :content (:content msg)
                        :pursuing-DS ds-id})))

     final-state)))
