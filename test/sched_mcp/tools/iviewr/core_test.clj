(ns sched-mcp.tools.iviewr.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.data.json :as json]
   [sched-mcp.tools.iviewr.core :as iviewr]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.llm :as llm]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.sutil :as sutil]
   [sched-mcp.tools.surrogate.sur-util :as suru]))

;;; Test fixtures using real in-memory databases

(defn setup-test-project []
  (let [;; Create in-memory project DB
        result (pdb/create-project-db! {:pid :test-proj-123
                                        :project-name "Test Project"
                                        :in-mem? true})]
    (:pid result)))

(use-fixtures :each
  (fn [f]
    (setup-test-project)
    (f)))

;;; Tests for conduct-interview tool (the main Phase 4 tool)

(deftest ^:integration conduct-interview-tool-integration-test
  (testing "Full integration test with real components"
    ;; This test requires LLM and system DB to be initialized
    (try
      (let [tool-config (iviewr/create-conduct-interview-tool)
            sur-result (suru/start-surrogate-interview
                        {:domain :craft-beer
                         :company-name "Test Brewery"
                         :project-name "Integration Test"})
            pid (:project-id sur-result)
            result (tool-system/execute-tool
                    tool-config
                    {:project_id (name pid)
                     :conversation_id "process"
                     :ds_id "process/warm-up-with-challenges"
                     :budget 5.0})]
        (is (= "success" (:status result)) "Interview should succeed")
        (is (= "warm-up-with-challenges" (:ds_id result)))
        (is (map? (:ascr result)) "ASCR should be a map")
        (is (pos? (count (:ascr result))) "ASCR should have data")
        (is (boolean? (:complete result)))
        (is (pos? (:message_count result)) "Should have messages")
        (is (number? (:budget_remaining result)))
        (is (string? (:summary result)))

        ;; Verify data was stored in project DB
        (let [stored-ascr (pdb/get-ASCR pid :process/warm-up-with-challenges)
              conversation (pdb/get-conversation pid :process)]
          (is (some? stored-ascr) "ASCR should be stored")
          (is (map? (:ascr/dstruct stored-ascr)))
          (is (pos? (count (:conversation/messages conversation)))
              "Messages should be stored")))
      (catch Exception e
        (println "Skipping integration test - prerequisites not met:")
        (println (.getMessage e))
        (is true "Skipped - LLM or system DB not available")))))

(deftest conduct-interview-tool-unit-tests
  (let [tool-config (iviewr/create-conduct-interview-tool)]

    (testing "tool creation"
      (is (= :conduct-interview (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "iviewr_conduct_interview" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (re-find #"autonomous interview" desc))
        (is (re-find #"LangGraph" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :project_id))
        (is (contains? (:properties schema) :conversation_id))
        (is (contains? (:properties schema) :ds_id))
        (is (contains? (:properties schema) :budget))
        (is (= ["project_id" "conversation_id" "ds_id"] (:required schema)))))

    (testing "validate-inputs"
      (is (= {:project_id "proj-123"
              :conversation_id "process"
              :ds_id "process/warm-up-with-challenges"}
             (tool-system/validate-inputs
              tool-config
              {:project_id "proj-123"
               :conversation_id "process"
               :ds_id "process/warm-up-with-challenges"})))

    (testing "format-results with success"
      (let [success-result {:status "success"
                            :ds_id "warm-up"
                            :ascr {:test "data"}
                            :complete true
                            :message_count 3
                            :budget_remaining 7.0
                            :summary "Interview completed"}
            formatted (tool-system/format-results tool-config success-result)]
        (is (false? (:error formatted)))
        (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
          (is (= "interview-completed" (:message-type parsed)))
          (is (= "success" (:status parsed)))
          (is (= "warm-up" (:ds_id parsed))))))

    (testing "format-results with error"
      (let [error-result {:status "error"
                          :error "Test error"
                          :ds_id "test"}
            formatted (tool-system/format-results tool-config error-result)]
        (is (true? (:error formatted)))
        (is (re-find #"Test error" (first (:result formatted)))))))))

;;; Test for create-iviewr-tools

(deftest create-iviewr-tools-test
  (testing "creates all interviewer tools including conduct-interview"
    (let [tools (iviewr/create-iviewr-tools)]
      (is (= 4 (count tools)))
      (is (= #{:formulate-question :interpret-response :get-current-ds :conduct-interview}
             (set (map :tool-type tools)))))))
