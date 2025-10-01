(ns sched-mcp.tools.orch.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.data.json :as json]
   [sched-mcp.tools.orch.core :as sut]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.sutil :as sutil]
   [datahike.api :as d]))

;;; Test fixtures and mock data

;; Mock the database connections
(defn setup-mock-databases []
  ;; Initialize the project database atom to prevent connection attempts
  (swap! sutil/databases-atm assoc :proj-123 {:mock "project-db"})
  (swap! sutil/databases-atm assoc :conv-456 {:mock "conversation-db"}))

(defn teardown-mock-databases []
  ;; Clean up the mock databases
  (swap! sutil/databases-atm dissoc :proj-123 :conv-456))

(use-fixtures :each (fn [f]
                      (setup-mock-databases)
                      (f)
                      (teardown-mock-databases)))

(def mock-ds-list
  [:process/warm-up-with-challenges
   :process/detailed-flow
   :data/product-info
   :data/resource-info])

(def mock-ds-info
  {:process/warm-up-with-challenges
   {:DS {:interview-objective "Learn about manufacturing process and challenges"}
    :interview-objective "Learn about manufacturing process and challenges"}
   :process/detailed-flow
   {:DS {:interview-objective "Get detailed process flow information"}
    :interview-objective "Get detailed process flow information"}
   :data/product-info
   {:DS {:interview-objective "Gather product data"}
    :interview-objective "Gather product data"}
   :data/resource-info
   {:DS {:interview-objective "Collect resource information"}
    :interview-objective "Collect resource information"}})

(def mock-ascr
  {:description "We make craft beer"
   :challenges ["Supply chain" "Quality control"]})

(def mock-system-atom (atom {}))

;;; Tests for get-next-ds tool

(deftest get-next-ds-tool-tests
  (let [tool-config (sut/create-get-next-ds-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :get-next-ds (:tool-type tool-config)))
      (is (= mock-system-atom (:system-atom tool-config))))

    (testing "tool-name"
      (is (= "orch_get_next_ds" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (re-find #"Discovery Schema status" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :project_id))
        (is (contains? (:properties schema) :conversation_id))
        (is (= ["project_id" "conversation_id"] (:required schema)))))

    (testing "execute-tool with available DS"
      (with-redefs [sdb/system-DS? (constantly mock-ds-list)
                    pdb/list-ASCR (constantly [:process/warm-up-with-challenges])
                    pdb/get-ASCR (constantly {:ascr/dstruct mock-ascr
                                              :ascr/completed? false
                                              :ascr/budget-left 8})
                    pdb/get-active-DS-id (constantly :process/warm-up-with-challenges)
                    sdb/get-DS-instructions (fn [ds-id] (get mock-ds-info ds-id))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"})]
          (is (map? result))
          (is (contains? result :available_ds))
          (is (= 4 (:total_available result)))
          (is (= 0 (:completed_count result)))
          (is (= "warm-up-with-challenges" (:current_active_ds result)))
          (is (= "conv-456" (:current_conversation result)))
          (is (true? (:recommendation_needed result)))
          (is (true? (:orchestrator_guide_available result))))))

    (testing "execute-tool with error"
      (with-redefs [sdb/system-DS? (fn [] (throw (Exception. "DB error")))]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"})]
          (is (contains? result :error))
          (is (re-find #"DB error" (:error result))))))

    (testing "format-results"
      (let [success-result {:available_ds [{:ds_id "process/test" :status "not-started"}]
                            :completed_count 1
                            :total_available 4
                            :current_active_ds "process/test"
                            :current_conversation "conv-456"
                            :project_ASCRs {}
                            :recommendation_needed true
                            :orchestrator_guide_available true}
            error-result {:error "Test error"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "orchestration-status" (:message-type parsed)))
            (is (= 4 (:total_available parsed)))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Test error" (first (:result formatted)))))))))

;;; Tests for start-ds-pursuit tool

(deftest start-ds-pursuit-tool-tests
  (let [tool-config (sut/create-start-ds-pursuit-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :start-ds-pursuit (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "orch_start_ds_pursuit" (tool-system/tool-name tool-config))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :ds_id))
        (is (contains? (:properties schema) :budget))
        (is (= ["project_id" "conversation_id" "ds_id"] (:required schema)))))

    (testing "execute-tool with valid DS"
      (with-redefs [sdb/get-DS-instructions (fn [ds-id] (get mock-ds-info ds-id mock-ds-info))
                    pdb/put-active-DS-id (constantly nil)
                    pdb/ASCR-exists? (constantly false)
                    pdb/init-ASCR! (constantly nil)
                    sutil/connect-atm (constantly (atom {:mock "connection"}))
                    d/q (constantly 12345)
                    d/transact (constantly nil)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "process/warm-up-with-challenges"
                       :budget 10})]
          (is (map? result))
          (is (= "warm-up-with-challenges" (:ds_id result)))
          (is (= "Learn about manufacturing process and challenges"
                 (:interview_objective result)))
          (is (= 10 (:budget result)))
          (is (= "Started DS pursuit" (:status result))))))

    (testing "execute-tool with DS not found"
      (with-redefs [sdb/get-DS-instructions (constantly "")]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "nonexistent"})]
          (is (contains? result :error))
          (is (re-find #"not found" (:error result))))))

    (testing "execute-tool with error"
      (with-redefs [sdb/get-DS-instructions (fn [ds-id] (get mock-ds-info ds-id mock-ds-info))
                    pdb/put-active-DS-id (fn [& _] (throw (Exception. "DB error")))]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "process/warm-up-with-challenges"})]
          (is (contains? result :error))
          (is (re-find #"DB error" (:error result))))))

    (testing "format-results"
      (let [success-result {:ds_id "process/test"
                            :interview_objective "Test objective"
                            :ds_template {:some "template"}
                            :budget 10
                            :status "Started DS pursuit"}
            error-result {:error "Start failed"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "ds-pursuit-started" (:message-type parsed)))
            (is (= "process/test" (:ds_id parsed)))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Start failed" (first (:result formatted)))))))))

;;; Tests for complete-ds tool

(deftest complete-ds-tool-tests
  (let [tool-config (sut/create-complete-ds-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :complete-ds (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "orch_complete_ds" (tool-system/tool-name tool-config))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (contains? (:properties schema) :final_notes))
        (is (= ["project_id" "conversation_id"] (:required schema)))))

    (testing "execute-tool with active DS"
      (with-redefs [sutil/connect-atm (constantly (atom {:mock "connection"}))
                    pdb/get-active-DS-id (constantly :process/warm-up-with-challenges)
                    pdb/get-ASCR (constantly {:ascr/dstruct mock-ascr
                                              :ascr/completed? false})
                    pdb/mark-ASCR-complete! (constantly nil)
                    d/transact (constantly nil)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :final-notes "Interview completed successfully"})]
          (is (map? result))
          (is (true? (:success result)))
          (is (= "process/warm-up-with-challenges" (:ds_id result)))
          (is (contains? result :final_ascr))
          (is (= {:valid true} (:validation_results result))))))

    (testing "execute-tool with no active DS"
      (with-redefs [sutil/connect-atm (constantly (atom {:mock "connection"}))
                    pdb/get-active-DS-id (constantly nil)]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"})]
          (is (contains? result :error))
          (is (re-find #"No active DS" (:error result))))))

    (testing "format-results"
      (let [success-result {:success true
                            :ds_id "process/test"
                            :final_ascr {:some "data"}
                            :validation_results {:valid true}}
            error-result {:error "Complete failed"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "ds-completed" (:message-type parsed)))
            (is (true? (:success parsed)))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Complete failed" (first (:result formatted)))))))))

;;; Tests for get-progress tool

(deftest get-progress-tool-tests
  (let [tool-config (sut/create-get-progress-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :get-progress (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "orch_get_progress" (tool-system/tool-name tool-config))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= ["project_id"] (:required schema)))))

    (testing "execute-tool with progress data"
      (with-redefs [pdb/get-interview-progress
                    (constantly {:completed_ds 2
                                 :total_ds 4
                                 :current_phase "data-gathering"
                                 :progress_percentage 50})]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"})]
          (is (map? result))
          (is (= 2 (:completed_ds result)))
          (is (= 4 (:total_ds result)))
          (is (= "data-gathering" (:current_phase result)))
          (is (= 50 (:progress_percentage result))))))

    (testing "execute-tool with error"
      (with-redefs [pdb/get-interview-progress
                    (fn [& _] (throw (Exception. "Progress error")))]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"})]
          (is (contains? result :error))
          (is (re-find #"Progress error" (:error result))))))

    (testing "format-results"
      (let [success-result {:completed_ds 2
                            :total_ds 4
                            :current_phase "data-gathering"
                            :progress_percentage 50}
            error-result {:error "Progress failed"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "interview-progress" (:message-type parsed)))
            (is (= 50 (:progress_percentage parsed)))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Progress failed" (first (:result formatted)))))))))

;;; Test for create-orchestrator-tools

(deftest create-orchestrator-tools-test
  (testing "creates all four orchestrator tools"
    (let [tools (sut/create-orchestrator-tools mock-system-atom)]
      (is (= 4 (count tools)))
      (is (= #{:get-next-ds :start-ds-pursuit :complete-ds :get-progress}
             (set (map :tool-type tools))))
      (is (every? #(= mock-system-atom (:system-atom %)) tools)))))