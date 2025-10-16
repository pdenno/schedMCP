(ns sched-mcp.interviewing.interview-graph-test
  "Integration tests for the complete interview loop."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.interviewing.interview-graph :as igraph]))

(deftest full-interview-loop-test
  (testing "Complete interview loop with mock nodes"
    (let [initial-state (istate/make-interview-state
                         {:ds-id :process/warm-up
                          :pid :craft-beer-123
                          :cid :process
                          :budget-left 10.0})
          final-state (igraph/run-interview initial-state)]

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
      (is (< (:budget-left final-state) 10.0)
          "Budget should be less than initial")
      (is (= 7.0 (:budget-left final-state))
          "Budget should be decremented 3 times (3 questions)"))))

(deftest budget-exhaustion-test
  (testing "Interview stops when budget is exhausted"
    (let [initial-state (istate/make-interview-state
                         {:ds-id :process/warm-up
                          :pid :craft-beer-123
                          :cid :process
                          :budget-left 1.5}) ; Only enough for 1 question
          final-state (igraph/run-interview initial-state)]

      ;; Should complete due to budget exhaustion
      (is (= true (:complete? final-state))
          "Interview should complete when budget exhausted")

      ;; Should have asked at least one question
      (is (>= (count (:messages final-state)) 2)
          "Should have at least one Q&A pair")

      ;; Budget should be exhausted or near zero
      (is (<= (:budget-left final-state) 0.5)
          "Budget should be nearly exhausted"))))

(deftest initial-state-preservation-test
  (testing "Initial state fields are preserved"
    (let [initial-state (istate/make-interview-state
                         {:ds-id :process/warm-up
                          :pid :craft-beer-123
                          :cid :process})
          final-state (igraph/run-interview initial-state)]

      (is (= :process/warm-up (:ds-id final-state))
          "DS ID should be preserved")
      (is (= :craft-beer-123 (:pid final-state))
          "Project ID should be preserved")
      (is (= :process (:cid final-state))
          "Conversation ID should be preserved"))))
