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

(def ^:diag diag (atom nil))

(defn build-interview-graph
  "Build the interview loop graph with LLM and surrogate integration.

   Flow:
   START → check-budget → formulate → get-answer → interpret → evaluate → (conditional)
     if complete? = false → check-budget (loop back)
     if complete? = true  → END

   Note: Budget is checked BEFORE formulating questions to avoid wasting LLM calls."
  []
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

    (.compile graph)))

;;; ============================================================================
;;; Mock Graph for Testing (Phase 2)
;;; ============================================================================

(defn build-mock-interview-graph
  "Build the interview loop graph with MOCK nodes for fast testing without LLM calls.

   Flow:
   START → check-budget → formulate → get-answer → interpret → evaluate → (conditional)
     if complete? = false → check-budget (loop back)
     if complete? = true  → END"
  []
  (let [schema (-> (istate/interview-state-schema) lg/make-schema)
        factory (lg/make-state-factory
                 (fn [init-data]
                   (let [clj-data (into {} init-data)
                         keywordized (into {} (map (fn [[k v]] [(keyword k) v]) clj-data))]
                     (istate/interview-state->agent-state
                      (istate/map->InterviewState keywordized)))))
        graph (StateGraph. schema factory)]

    ;; Add nodes - using MOCK implementations
    (.addNode graph "check-budget" (lg/make-async-node nodes/check-budget))
    (.addNode graph "formulate" (lg/make-async-node nodes/mock-formulate-question))
    (.addNode graph "get-answer" (lg/make-async-node nodes/mock-get-answer))
    (.addNode graph "interpret" (lg/make-async-node nodes/mock-interpret-response))
    (.addNode graph "evaluate" (lg/make-async-node nodes/evaluate-completion))

    ;; Add unconditional edges
    (.addEdge graph StateGraph/START "check-budget")
    (.addEdge graph "check-budget" "formulate")
    (.addEdge graph "formulate" "get-answer")
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
                                 "check-budget"))))
                          (java.util.Map/of
                           StateGraph/END StateGraph/END
                           "check-budget" "check-budget"))

    (.compile graph)))

;;; ============================================================================
;;; Execution
;;; ============================================================================

(defn run-interview
  "Run an interview loop with LLM and surrogate integration.
   Returns the final InterviewState with completed ASCR."
  [initial-interview-state]
  (let [graph (build-interview-graph)
        init-data (istate/interview-state->map initial-interview-state)
        results (vec (.stream graph init-data))
        final-agent-state (-> results last .state)]
    (istate/agent-state->interview-state final-agent-state)))

(defn run-mock-interview
  "Run an interview loop with MOCK nodes for fast testing.
   Returns the final InterviewState."
  [initial-interview-state]
  (let [graph (build-mock-interview-graph)
        init-data (istate/interview-state->map initial-interview-state)
        results (vec (.stream graph init-data))
        final-agent-state (-> results last .state)]
    (istate/agent-state->interview-state final-agent-state)))
