(ns sched-mcp.integration-test
  "Integration test for Discovery Schema flow"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.tools.registry :as registry]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.dev-helpers :as dev]))

(defn call-tool
  "Helper to call a tool by name"
  [tool-name params]
  (let [tool-spec (first (filter #(= (:name %) tool-name)
                                 registry/tool-specs))]
    (when tool-spec
      ((:tool-fn tool-spec) params))))

(deftest test-ds-flow
  (testing "Complete DS flow from start to finish"
    ;; Clean up any existing test data
    (dev/reset-interview-db! :integration-test)

    ;; Start interview
    (let [start-result (call-tool "start_interview"
                                  {:project_name "Integration Test"
                                   :domain "testing"})]
      (is (= true (:success start-result)))
      (is (:project_id start-result))
      (is (:conversation_id start-result))

      (let [project-id (:project_id start-result)
            conv-id (:conversation_id start-result)]

        ;; Get next DS recommendation
        (testing "Orchestrator recommends warm-up"
          (let [next-ds (call-tool "get_next_ds"
                                   {:project_id project-id
                                    :conversation_id conv-id})]
            (is (= "process/warm-up-with-challenges" (:ds_id next-ds)))
            (is (= "process" (:interviewer_type next-ds)))))

        ;; Start DS pursuit
        (testing "Start DS pursuit"
          (let [pursuit (call-tool "start_ds_pursuit"
                                   {:project_id project-id
                                    :conversation_id conv-id
                                    :ds_id "process/warm-up-with-challenges"
                                    :budget 10})]
            (is (:pursuit_id pursuit))
            (is (= 10 (:budget pursuit)))
            (is (:ds_instructions pursuit))))

        ;; Get current DS
        (testing "Get current DS and ASCR"
          (let [current (call-tool "get_current_ds"
                                   {:project_id project-id
                                    :conversation_id conv-id})]
            (is (= "process/warm-up-with-challenges" (:ds_id current)))
            (is (map? (:ds_template current)))
            (is (map? (:current_ascr current)))))

        ;; Formulate question (placeholder for now)
        (testing "Formulate question"
          (let [question (call-tool "formulate_question"
                                    {:project_id project-id
                                     :conversation_id conv-id
                                     :ds_id "process/warm-up-with-challenges"})]
            (is (:question question))
            (is (string? (get-in question [:question :text])))))

        ;; Interpret response (placeholder for now)
        (testing "Interpret response"
          (let [scr (call-tool "interpret_response"
                               {:project_id project-id
                                :conversation_id conv-id
                                :ds_id "process/warm-up-with-challenges"
                                :answer "We make craft beer and have bottleneck issues"
                                :question_asked "What do you make?"})]
            (is (:scr scr))
            (is (> (:confidence scr) 0))))

        ;; Complete DS
        (testing "Complete DS pursuit"
          (let [complete (call-tool "complete_ds"
                                    {:project_id project-id
                                     :conversation_id conv-id
                                     :final_notes "Test completion"})]
            (is (= true (:success complete)))
            (is (= "process/warm-up-with-challenges" (:ds_id complete)))))

        ;; Check next recommendation changes
        (testing "Next DS recommendation after completion"
          (let [next-ds (call-tool "get_next_ds"
                                   {:project_id project-id
                                    :conversation_id conv-id})]
            (is (= "process/scheduling-problem-type" (:ds_id next-ds)))))))

    ;; Clean up
    (dev/reset-interview-db! :integration-test)))

(defn run-tests
  "Run the integration tests"
  []
  (clojure.test/run-tests 'sched-mcp.integration-test))
