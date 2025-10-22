(ns sched-mcp.tools.iviewr.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.data.json :as json]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.tools.surrogate.sur-util :as suru]))

(deftest conduct-interview-tool-integration-test
  (testing "Full integration test with real components and in-mem? = true project"
    ;; This test requires LLM and system DB to be initialized
    (let [{:keys [pid]} (suru/start-surrogate-interview
                         {:domain "craft beer" :project-name "Sur Craft Beer" :in-mem? true})]
      (sdb/with-current-project [pid]
        (let [result (tool-system/execute-tool
                      {:tool-type :conduct-interview}
                      {:project_id (name pid)
                       :conversation_id "process"
                       :ds_id "process/warm-up-with-challenges"
                       :budget 1.0})]
          (is (= "success" (:status result)) "Interview should succeed")
          (is (= "warm-up-with-challenges" (:ds_id result)))
          (is (map? (:ascr result)) "ASCR should be a map")
          (is (pos? (count (:ascr result))) "ASCR should have data")
          (is (boolean? (:complete result)))

          (is (number? (:budget_remaining result)))
          (is (string? (:summary result)))

          ;; Verify data was stored in project DB
          (let [stored-ascr (pdb/get-ASCR pid :process/warm-up-with-challenges)
                conversation (pdb/get-conversation pid :process)]
            (is (some? stored-ascr) "ASCR should be stored")
            (is (map? (:ascr/dstruct stored-ascr)))
            (is (pos? (count (:conversation/messages conversation)))
                "Messages should be stored")))))))

(deftest conduct-interview-tool-unit-tests
  (let [tool-config {:tool-type :conduct-interview}]
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
                              :budget_remaining 0.7
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
