(ns sched-mcp.interviewing.interview-graph
  "Interview graph construction and execution."
  (:require
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.interviewing.interview-nodes :as nodes]
   [sched-mcp.interviewing.lg-util :as lg])
  (:import [org.bsc.langgraph4j StateGraph]))

;;; ============================================================================
;;; Graph Construction
;;; ============================================================================

(defn build-interview-graph
  "Build the interview loop graph with conditional edges.

   Flow:
   START → formulate → check-budget → get-answer → interpret → evaluate → (conditional)
     if complete? = false → formulate (loop back)
     if complete? = true  → END"
  []
  (let [schema (istate/interview-state-schema)
        factory (lg/make-state-factory
                 (fn [init-data]
                   ;; init-data is a Java Map with string keys
                   ;; Convert to Clojure map and keywordize keys
                   (let [clj-data (into {} init-data)
                         keywordized (into {} (map (fn [[k v]] [(keyword k) v]) clj-data))]
                     (istate/interview-state->agent-state
                      (istate/map->InterviewState keywordized)))))
        graph (StateGraph. (lg/make-schema schema) factory)]

    ;; Add nodes
    (.addNode graph "formulate" (lg/make-async-node nodes/mock-formulate-question))
    (.addNode graph "check-budget" (lg/make-async-node nodes/check-budget))
    (.addNode graph "get-answer" (lg/make-async-node nodes/mock-get-answer))
    (.addNode graph "interpret" (lg/make-async-node nodes/mock-interpret-response))
    (.addNode graph "evaluate" (lg/make-async-node nodes/evaluate-completion))

    ;; Add unconditional edges
    (.addEdge graph StateGraph/START "formulate")
    (.addEdge graph "formulate" "check-budget")
    (.addEdge graph "check-budget" "get-answer")
    (.addEdge graph "get-answer" "interpret")
    (.addEdge graph "interpret" "evaluate")

    ;; Add conditional edge based on complete? state
    (.addConditionalEdges graph "evaluate"
                          (lg/make-async-edge
                           (fn [state]
                             (let [istate (istate/agent-state->interview-state state)
                                   complete? (:complete? istate)]
                               (if complete?
                                 StateGraph/END
                                 "formulate"))))
                          (java.util.Map/of
                           StateGraph/END StateGraph/END
                           "formulate" "formulate"))

    (.compile graph)))

;;; ============================================================================
;;; Execution
;;; ============================================================================

(defn run-interview
  "Run an interview loop starting with the given initial state.
   Returns the final InterviewState."
  [initial-interview-state]
  (let [graph (build-interview-graph)
        init-data (istate/interview-state->map initial-interview-state)
        results (vec (.stream graph init-data))
        final-agent-state (-> results last .state)]
    (istate/agent-state->interview-state final-agent-state)))
