(ns sched-mcp.simple-test
  "Simple test for Discovery Schema tools without database dependencies"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.ds-loader-v2 :as ds]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.tools.iviewr.core :as interviewer]
   [sched-mcp.tools.orchestrator.core :as orchestrator]))

(deftest test-ds-loading
  (testing "DS loader functionality"
    ;; List available DS
    (let [available (ds/list-available-ds)]
      (is (map? available))
      (is (contains? available :process))
      (is (contains? available :data))
      (is (pos? (count (:process available)))))

    ;; Load specific DS
    (let [warm-up (ds/get-ds-by-id :process/warm-up-with-challenges)]
      (is (map? warm-up))
      (is (= :process/warm-up-with-challenges (:ds-id warm-up)))
      (is (string? (:interview-objective warm-up)))
      (is (map? (:eads warm-up))))))

(deftest test-tool-definitions
  (testing "Tool system definitions"
    ;; Create mock system atom
    (let [system-atom (atom {})
          formulate-tool (interviewer/create-formulate-question-tool system-atom)]

      ;; Test tool metadata
      (is (= "iviewr_formulate_question" (tool-system/tool-name formulate-tool)))
      (is (string? (tool-system/tool-description formulate-tool)))
      (is (map? (tool-system/tool-schema formulate-tool)))

      ;; Test validation
      (is (thrown? Exception
                   (tool-system/validate-inputs formulate-tool {})))
      (is (map? (tool-system/validate-inputs formulate-tool
                                             {:project-id "test"
                                              :conversation-id "conv"
                                              :ds-id "process/warm-up"}))))))

(deftest test-ds-structure
  (testing "Discovery Schema structure parsing"
    (let [flow-shop (ds/get-ds-by-id :process/flow-shop)]
      (is (= "EADS-INSTRUCTIONS" (:message-type flow-shop)))
      (is (contains? (:eads flow-shop) :process-id))
      (is (contains? (:eads flow-shop) :subprocesses)))

    (let [orm (ds/get-ds-by-id :data/orm)]
      (is (contains? (:eads orm) :inquiry-areas))
      (is (vector? (:inquiry-areas (:eads orm)))))))

(defn run-tests []
  (clojure.test/run-tests 'sched-mcp.simple-test))
