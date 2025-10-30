(ns sched-mcp.interviewing.checkpoint-replay-test
  "Test checkpoint replay functionality with real LLM calls."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.checkpoint :as ckpt]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.interviewing.interview-nodes :as nodes]
   [sched-mcp.interviewing.lg-util :as lgu]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :refer [log!]]))

(deftest ^:integration replay-from-checkpoint-test
  "Test replaying an interview from a checkpoint.
   This test makes a real LLM call and takes 40+ seconds.
   Run with: (clojure.test/run-tests 'sched-mcp.interviewing.checkpoint-replay-test)"

  (testing "Load checkpoint and call interpret-response"
    (let [file-cp (ckpt/file-checkpointer)
          cps (ckpt/load-checkpoints file-cp :sur-craft-beer :data/orm)
          _ (log! :info (str "Found " (count cps) " checkpoints"))

          ;; Find checkpoint after first Q&A
          cp (first (filter #(= "after-first-qa-ready-to-interpret"
                                (get-in % [:checkpoint/metadata :step]))
                            cps))
          _ (is (some? cp) "Should find checkpoint with correct metadata")

          state (:checkpoint/state cp)
          _ (testing "Checkpoint has expected structure"
              (is (= 2 (count (:messages state))) "Should have 2 messages (Q&A)")
              (is (empty? (:ascr state)) "ASCR should be empty")
              (is (= 0.95 (:budget-left state)) "Budget should be 0.95"))

          ;; Prepare state
          ds-obj (sdb/get-DS-instructions :data/orm)
          ds-instructions {:DS ds-obj
                           :interview-objective (:DS/interview-objective ds-obj)}
          enriched-state (assoc state :ds-instructions ds-instructions)
          istate-record (istate/map->InterviewState enriched-state)
          agent-state (istate/interview-state->agent-state istate-record)

          _ (log! :info "=== Calling interpret-response with REAL LLM ===")
          _ (log! :info "This will take 40+ seconds...")

          ;; Call interpret-response (REAL LLM CALL - takes 40+ seconds)
          ;; Node returns an update map {:ascr scr}, not an AgentState
          update-map (nodes/interpret-response agent-state)

          ;; Apply the update to get new AgentState (simulates LangGraph behavior)
          result-agent-state (lgu/apply-node-update agent-state update-map)
          result-istate (istate/agent-state->interview-state result-agent-state)]

      (log! :info "=== LLM call completed ===")

      (testing "interpret-response populated ASCR"
        (is (seq (:ascr result-istate)) "ASCR should be populated")
        (log! :info (str "ASCR keys: " (keys (:ascr result-istate))))

        (when (seq (:ascr result-istate))
          (println "\n=== ASCR Content ===")
          (clojure.pprint/pprint (:ascr result-istate))

          ;; For ORM DS, we expect inquiry-areas
          (when (contains? (:ascr result-istate) "inquiry-areas")
            (is (vector? (get (:ascr result-istate) "inquiry-areas"))
                "inquiry-areas should be a vector")
            (log! :info (str "Found "
                             (count (get (:ascr result-istate) "inquiry-areas"))
                             " inquiry areas"))))

        (is (< (:budget-left result-istate) 0.95)
            "Budget should have decreased")
        (log! :info (str "Budget after: " (:budget-left result-istate)))))))

(defn ^:diag run-checkpoint-replay-test
  "Run the checkpoint replay test from REPL.
   Usage: (run-checkpoint-replay-test)"
  []
  (clojure.test/run-tests 'sched-mcp.interviewing.checkpoint-replay-test))
