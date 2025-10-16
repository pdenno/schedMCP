(ns sched-mcp.interviewing.interview-state-test
  "Tests for interview state management and reduction."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.interview-state :as istate]))

(deftest make-interview-state-test
  (testing "Creating interview state with defaults"
    (let [state (istate/make-interview-state
                 {:ds-id :process/warm-up
                  :pid :test-project
                  :cid :process})]
      (is (= :process/warm-up (:ds-id state)))
      (is (= {} (:ascr state)) "ASCR starts empty")
      (is (= [] (:messages state)) "Messages start empty")
      (is (= 10.0 (:budget-left state)) "Default budget is 10.0")
      (is (= false (:complete? state)) "Not complete initially")
      (is (= :test-project (:pid state)))
      (is (= :process (:cid state)))))

  (testing "Creating interview state with custom budget"
    (let [state (istate/make-interview-state
                 {:ds-id :data/orm
                  :pid :test-project
                  :cid :data
                  :budget-left 5.0})]
      (is (= 5.0 (:budget-left state)) "Custom budget respected"))))

(deftest conversion-test
  (testing "InterviewState to map conversion"
    (let [state (istate/make-interview-state
                 {:ds-id :process/warm-up
                  :pid :test-project
                  :cid :process})
          state-map (istate/interview-state->map state)]
      (is (map? state-map))
      (is (= :process/warm-up (get state-map "ds-id")))
      (is (= {} (get state-map "ascr")))
      (is (= [] (get state-map "messages")))))

  (testing "Round-trip conversion"
    (let [original (istate/make-interview-state
                    {:ds-id :process/warm-up
                     :pid :test-project
                     :cid :process})
          agent-state (istate/interview-state->agent-state original)
          recovered (istate/agent-state->interview-state agent-state)]
      (is (= (:ds-id original) (:ds-id recovered)))
      (is (= (:ascr original) (:ascr recovered)))
      (is (= (:messages original) (:messages recovered)))
      (is (= (:budget-left original) (:budget-left recovered)))
      (is (= (:complete? original) (:complete? recovered)))
      (is (= (:pid original) (:pid recovered)))
      (is (= (:cid original) (:cid recovered))))))

(deftest helper-functions-test
  (testing "add-message returns correct update map"
    (let [update (istate/add-message :system "What challenges?")]
      (is (= {:from :system :content "What challenges?"}
             (get update :messages)))))

  (testing "update-ascr returns correct update map"
    (let [scr {:challenges ["Demand variability"]}
          update (istate/update-ascr scr)]
      (is (= scr (get update :ascr)))))

  (testing "decrement-budget returns correct update map"
    (let [update (istate/decrement-budget 10.0 1.0)]
      (is (= 9.0 (get update :budget-left)))))

  (testing "mark-complete returns correct update map"
    (let [update (istate/mark-complete)]
      (is (= true (get update :complete?))))))

(deftest state-reduction-simulation-test
  (testing "Simulating ASCR reduction with merge"
    (let [initial-ascr {}
          scr1 {:challenges ["Seasonal demand"]}
          scr2 {:challenges ["Equipment limits"] :motivation "make-to-stock"}
          scr3 {:description "Craft brewery scheduling"}

          ;; Simulate what the reducer channel will do
          after-1 (merge initial-ascr scr1)
          after-2 (merge after-1 scr2)
          after-3 (merge after-2 scr3)]

      (is (= {:challenges ["Seasonal demand"]} after-1))
      (is (= {:challenges ["Equipment limits"]
              :motivation "make-to-stock"} after-2)
          "Note: simple merge overwrites :challenges, not appends")
      (is (= {:challenges ["Equipment limits"]
              :motivation "make-to-stock"
              :description "Craft brewery scheduling"} after-3)))))
