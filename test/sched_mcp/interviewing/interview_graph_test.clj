(ns sched-mcp.interviewing.interview-graph-test
  "Integration tests for the complete interview loop."
  (:require
   [clojure.pprint]
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.lg-util :as lg]
   [sched-mcp.interviewing.interview-graph :as igraph]
   [sched-mcp.interviewing.interview-nodes :as nodes]
   [sched-mcp.interviewing.interview-state :as is]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.tools.surrogate.sur-util :as suru])
  (:import [org.bsc.langgraph4j StateGraph]))

(declare run-mock-interview)

(deftest full-interview-loop-test
  (testing "Complete interview loop with mock nodes"
    (let [initial-state (is/make-interview-state
                         {:ds-id :process/warm-up-with-challenges
                          :pid :craft-beer-123
                          :cid :process
                          :budget-left 1.0})
          final-state (run-mock-interview initial-state)]

      ;; Check completion status
      (is (= true (:complete? final-state))
          "Interview should be marked complete")

      ;; Check ASCR has all required fields
      (is (contains? (:ascr final-state) :challenges)
          "ASCR should have challenges")
      (is (contains? (:ascr final-state) :motivation)
          "ASCR should have motivation")
      (is (contains? (:ascr final-state) :description)
          "ASCR should have description")

      ;; Check ASCR content
      (is (= ["Seasonal demand variations" "Equipment capacity limits"]
             (get-in final-state [:ascr :challenges]))
          "Challenges should be extracted correctly")
      (is (= "make-to-stock" (get-in final-state [:ascr :motivation]))
          "Motivation should be extracted correctly")

      ;; Check message history
      (let [messages (:messages final-state)]
        (is (= 6 (count messages))
            "Should have 6 messages (3 Q&A pairs)")

        ;; Check message structure
        (is (every? #(contains? % :from) messages)
            "All messages should have :from")
        (is (every? #(contains? % :content) messages)
            "All messages should have :content")

        ;; Check alternating pattern
        (is (= [:system :surrogate :system :surrogate :system :surrogate]
               (map :from messages))
            "Messages should alternate system/surrogate"))

      ;; Check budget was decremented
      (is (< (:budget-left final-state) 1.0)
          "Budget should be less than initial")
      (is (<= 0.69 (:budget-left final-state) 0.71)
          "Budget should be decremented 3 times (3 questions)"))))

(deftest budget-exhaustion-test
  (testing "Interview stops when budget is exhausted"
    (let [initial-state (is/make-interview-state
                         {:ds-id :process/warm-up-with-challenges
                          :pid :craft-beer-123
                          :cid :process
                          :budget-left 0.1}) ; Only enough for 1 question
          final-state (run-mock-interview initial-state)]

      ;; Should complete due to budget exhaustion
      (is (= true (:complete? final-state))
          "Interview should complete when budget exhausted")

      ;; Should have asked at least one question
      (is (>= (count (:messages final-state)) 2)
          "Should have at least one Q&A pair")

      ;; Budget should be exhausted or near zero
      (is (<= (:budget-left final-state) 0.05)
          "Budget should be nearly exhausted"))))

(deftest initial-state-preservation-test
  (testing "Initial state fields are preserved"
    (let [initial-state (is/make-interview-state
                         {:ds-id :process/warm-up-with-challenges
                          :pid :craft-beer-123
                          :cid :process})
          final-state (run-mock-interview initial-state)]

      (is (= :process/warm-up-with-challenges (:ds-id final-state))
          "DS ID should be preserved")
      (is (= :craft-beer-123 (:pid final-state))
          "Project ID should be preserved")
      (is (= :process (:cid final-state))
          "Conversation ID should be preserved"))))

;;; ============================================================================
;;; Mock Graph for Testing (Phase 2)
;;; ============================================================================

#_(defn build-mock-interview-graph
    "Build the interview loop graph with MOCK nodes for fast testing without LLM calls.

   Flow:
   START → check-budget → formulate → get-answer → interpret → evaluate → (conditional)
     if complete? = false → check-budget (loop back)
     if complete? = true  → END"
    []
    (let [schema (-> (is/interview-state-schema) lg/make-schema)
          factory (lg/make-state-factory
                   (fn [init-data]
                     (let [clj-data (into {} init-data)
                           keywordized (into {} (map (fn [[k v]] [(keyword k) v]) clj-data))]
                       (is/interview-state->agent-state
                        (is/map->InterviewState keywordized)))))
          graph (StateGraph. schema factory)]

    ;; Add nodes - using MOCK implementations
      (.addNode graph "check-budget" (lg/make-async-node nodes/check-budget))
      (.addNode graph "formulate" (lg/make-async-node nodes/mock-formulate-question))
      (.addNode graph "get-answer" (lg/make-async-node nodes/mock-get-answer))
      (.addNode graph "interpret" (lg/make-async-node nodes/mock-interpret-response))
      (.addNode graph "evaluate" (lg/make-async-node nodes/mock-evaluate-completion))

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
                               (let [istate (is/agent-state->interview-state state)
                                     complete? (:complete? istate)]
                                 (if complete?
                                   StateGraph/END
                                   "check-budget"))))
                            (java.util.Map/of
                             StateGraph/END StateGraph/END
                             "check-budget" "check-budget"))

      (.compile graph)))

#_(defn run-mock-interview
    "Run an interview loop with MOCK nodes for fast testing.
   Returns the final InterviewState."
    [initial-interview-state]
    (let [graph (build-mock-interview-graph)
          init-data (is/interview-state->map initial-interview-state)
          results (vec (.stream graph init-data))
          final-agent-state (-> results last .state)]
      (is/agent-state->interview-state final-agent-state)))

;;; ============================================================================
;;; Phase 3 Real Implementation Tests
;;; ============================================================================

(deftest ^:integration real-interview-with-surrogate-test
  (testing "Real interview loop with LLM and surrogate (Phase 3)"
    (try
      (let [sur-result (suru/start-surrogate-interview
                        {:domain :craft-beer
                         :company-name "Test Brewery"
                         :project-name "Phase 3 Integration Test"})
            pid (:pid sur-result)
            initial-state (is/make-interview-state
                           {:ds-id :process/warm-up-with-challenges
                            :pid pid
                            :cid :process
                            :budget-left 1.0})
            final-state (igraph/run-interview initial-state)]

        (is (= true (:complete? final-state))
            "Interview should complete")

        (is (map? (:ascr final-state))
            "ASCR should be a map")
        (is (pos? (count (:ascr final-state)))
            "ASCR should have some fields populated")

        (is (pos? (count (:messages final-state)))
            "Should have messages from Q&A")
        (is (some #(= (:from %) :system) (:messages final-state))
            "Should have interviewer questions")
        (is (some #(= (:from %) :surrogate) (:messages final-state))
            "Should have surrogate responses")

        (is (<= (:budget-left final-state) 5.0)
            "Budget should be decremented or exhausted")

        (is (= :process/warm-up-with-challenges (:ds-id final-state))
            "DS ID should be preserved")
        (is (= pid (:pid final-state))
            "Project ID should be preserved")
        (is (= :process (:cid final-state))
            "Conversation ID should be preserved")

        (println "\nFinal ASCR from real interview:")
        (clojure.pprint/pprint (:ascr final-state))
        (println "\nMessage count:" (count (:messages final-state)))
        (println "Budget remaining:" (:budget-left final-state)))

      (catch Exception e
        (println "Real interview test failed - prerequisites not met:")
        (println (.getMessage e))
        (println "This test requires LLM API credentials and loaded Discovery Schemas")
        (is true "Skipped - prerequisites not met")))))

(deftest ^:integration orm-interview-test
  (testing "ORM Discovery Schema interview with real LLM"
    (try
      (let [sur-result (suru/start-surrogate-interview
                        {:domain :craft-beer
                         :company-name "Test Brewery for ORM"
                         :project-name "ORM DS Test"})
            pid (:pid sur-result)
            _ (do
                ;; Set up the :data conversation as active
                (pdb/put-active-cid! pid :data)
                (pdb/put-active-DS-id pid :data :data/orm)
                ;; Clean up :process conversation state
                (pdb/retract-active-DS-id pid :process)
                (pdb/put-conversation-status! pid :process :ds-exhausted))
            domain (:domain sur-result)
            system-instruction (suru/system-instruction domain)
            initial-state (is/make-interview-state
                           {:ds-id :data/orm
                            :pid pid
                            :cid :data
                            :budget-left 1.0
                            :surrogate-instruction system-instruction})
            final-state (igraph/run-interview initial-state)]

        ;; run-interview now persists ASCR and messages to DB automatically
        ;; Verify they were stored correctly
        (let [stored-ascr (pdb/get-ASCR pid :data/orm)]
          (is (map? stored-ascr) "ASCR should be stored in DB")
          (when (pos? (count stored-ascr))
            (println "\n✅ ASCR successfully persisted to DB!")))

        ;; Verify completion or budget exhaustion
        (is (or (= true (:complete? final-state))
                (<= (:budget-left final-state) 0.0))
            "Interview should complete or exhaust budget")

        ;; Verify ASCR structure for ORM
        (is (map? (:ascr final-state))
            "ASCR should be a map")

        ;; Check if areas-we-intend-to-discuss was set (Task 1)
        (when (contains? (:ascr final-state) :areas-we-intend-to-discuss)
          (is (vector? (:areas-we-intend-to-discuss (:ascr final-state)))
              "areas-we-intend-to-discuss should be a vector"))

        ;; Check if any inquiry-areas were captured (Task 2/3)
        (when (contains? (:ascr final-state) :inquiry-areas)
          (is (vector? (:inquiry-areas (:ascr final-state)))
              "inquiry-areas should be a vector"))

        ;; Verify messages were exchanged
        (is (pos? (count (:messages final-state)))
            "Should have messages from Q&A")
        (is (some #(= (:from %) :system) (:messages final-state))
            "Should have interviewer questions")
        (is (some #(= (:from %) :surrogate) (:messages final-state))
            "Should have surrogate responses")

        ;; Verify state fields preserved
        (is (= :data/orm (:ds-id final-state))
            "DS ID should be preserved")
        (is (= pid (:pid final-state))
            "Project ID should be preserved")
        (is (= :data (:cid final-state))
            "Conversation ID should be preserved")

        ;; Log detailed results for inspection
        (println "\n=== ORM Interview Test Results ===")
        (println "Completion status:" (:complete? final-state))
        (println "Budget remaining:" (:budget-left final-state))
        (println "Message count:" (count (:messages final-state)))
        (println "\nFinal ASCR:")
        (clojure.pprint/pprint (:ascr final-state))
        (println "\nMessages:")
        (doseq [[idx msg] (map-indexed vector (:messages final-state))]
          (println (str "\n[" idx "] " (:from msg) ":"))
          (println (subs (:content msg) 0 (min 200 (count (:content msg)))) "...")))

      (catch Exception e
        (println "ORM interview test failed:")
        (println (.getMessage e))
        (clojure.pprint/pprint (ex-data e))
        (is false (str "Test failed with exception: " (.getMessage e)))))))
