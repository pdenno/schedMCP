(ns sched-mcp.tools.iviewr.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.data.json :as json]
   [sched-mcp.tools.iviewr.core :as sut]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.llm :as llm]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.sutil :as sutil]))

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

(def mock-ds-json
  (json/write-str
   {:DS-id "process/warm-up-with-challenges"
    :name "Warm-up with Challenges"
    :interview-objective "Learn about the manufacturing process"
    :DS {:description {:type "string"
                       :description "Describe your manufacturing process"}
         :challenges {:type "array"
                      :description "What are your main challenges?"}}}))

(def mock-ds-full
  {:DS-id :process/warm-up-with-challenges
   :name "Warm-up with Challenges"
   :interview-objective "Learn about the manufacturing process"
   :DS {:description {:type "string"
                      :description "Describe your manufacturing process"}
        :challenges {:type "array"
                     :description "What are your main challenges?"}}})

(def mock-ascr
  {:description "We make craft beer"
   :challenges ["Supply chain issues" "Quality control"]})

(def mock-system-atom (atom {}))

;;; Tests for formulate-question tool

(deftest formulate-question-tool-tests
  (let [tool-config (sut/create-formulate-question-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :formulate-question (:tool-type tool-config)))
      (is (= mock-system-atom (:system-atom tool-config))))

    (testing "tool-name"
      (is (= "iviewr_formulate_question" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (pos? (count desc)))
        (is (re-find #"interview questions" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :project_id))
        (is (contains? (:properties schema) :conversation_id))
        (is (contains? (:properties schema) :ds_id))
        (is (= ["project_id" "conversation_id" "ds_id"] (:required schema)))))

    (testing "validate-inputs"
      (is (= {:project-id "proj-123"
              :conversation-id "conv-456"
              :ds-id "process/warm-up"}
             (tool-system/validate-inputs
              tool-config
              {:project-id "proj-123"
               :conversation-id "conv-456"
               :ds-id "process/warm-up"})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs tool-config {})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs
                    tool-config
                    {:project-id "proj-123"}))))

    (testing "execute-tool with successful LLM response"
      (with-redefs [sdb/get-discovery-schema-JSON (constantly mock-ds-json)
                    sdb/get-DS-instructions (constantly mock-ds-full)
                    pdb/get-ASCR (constantly {:ascr/dstruct mock-ascr
                                              :ascr/budget-left 8})
                    pdb/get-conversation (constantly {:conversation/messages []})
                    llm/init-llm! (constantly nil)
                    llm/agent-prompts (atom {:some "value"})
                    llm/ds-question-prompt (constantly "mock prompt")
                    llm/complete-json (constantly {:question-to-ask "What materials do you use?"
                                                   :help "List the main materials"
                                                   :rationale "Need material info"
                                                   :targets ["materials" "suppliers"]})
                    pdb/add-msg! (constantly 12345)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "process/warm-up-with-challenges"})]
          (is (map? result))
          (is (contains? result :question))
          (is (contains? result :context))
          (is (= 12345 (get-in result [:question :id])))
          (is (= "What materials do you use?" (get-in result [:question :text])))
          ;; Note: The code uses (name ds-id) so it strips the namespace
          (is (= "warm-up-with-challenges" (get-in result [:question :ds_id]))))))

    (testing "execute-tool with DS not found"
      (with-redefs [sdb/get-discovery-schema-JSON (constantly nil)
                    sdb/get-DS-instructions (constantly "")
                    ;; Also mock get-ASCR to prevent DB access
                    pdb/get-ASCR (constantly {:ascr/dstruct {}
                                              :ascr/budget-left 10})]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "nonexistent"})]
          (is (contains? result :error))
          (is (re-find #"not found" (:error result))))))

    (testing "execute-tool with LLM error falls back"
      (with-redefs [sdb/get-discovery-schema-JSON (constantly mock-ds-json)
                    sdb/get-DS-instructions (constantly mock-ds-full)
                    pdb/get-ASCR (constantly {:ascr/dstruct {}
                                              :ascr/budget-left 10})
                    pdb/get-conversation (constantly {:conversation/messages []})
                    llm/init-llm! (constantly nil)
                    llm/agent-prompts (atom {:some "value"})
                    llm/ds-question-prompt (fn [& _] (throw (Exception. "LLM error")))
                    pdb/add-msg! (constantly 99999)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "process/warm-up-with-challenges"})]
          (is (map? result))
          (is (= "Can you tell me more about your process?"
                 (get-in result [:question :text])))
          (is (true? (get-in result [:context :error_fallback]))))))

    (testing "format-results"
      (let [success-result {:question {:id 123
                                       :text "Test question?"
                                       :ds_id "process/test"
                                       :help "Test help"}
                            :context {:ds_objective "Test objective"
                                      :fields_remaining 5}}
            error-result {:error "Test error"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (vector? (:result formatted)))
          (is (= 1 (count (:result formatted))))
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "question-generated" (:message-type parsed)))
            (is (= 123 (get-in parsed [:question :id])))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Test error" (first (:result formatted)))))))))

;;; Tests for interpret-response tool

(deftest interpret-response-tool-tests
  (let [tool-config (sut/create-interpret-response-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :interpret-response (:tool-type tool-config)))
      (is (= mock-system-atom (:system-atom tool-config))))

    (testing "tool-name"
      (is (= "iviewr_interpret_response" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (re-find #"natural language answer" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :answer))
        (is (contains? (:properties schema) :question_asked))
        (is (= 5 (count (:required schema))))))

    (testing "validate-inputs"
      (let [valid-inputs {:project-id "proj-123"
                          :conversation-id "conv-456"
                          :ds-id "process/warm-up"
                          :answer "We use aluminum and steel"
                          :question-asked "What materials do you use?"}]
        (is (= valid-inputs
               (tool-system/validate-inputs tool-config valid-inputs)))

        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs
                      tool-config
                      (dissoc valid-inputs :answer))))))

    (testing "execute-tool with successful interpretation"
      (with-redefs [sdb/get-discovery-schema-JSON (constantly mock-ds-json)
                    sdb/get-DS-instructions (constantly mock-ds-full)
                    pdb/get-ASCR (constantly {:ascr/dstruct {}
                                              :ascr/budget-left 8})
                    pdb/add-msg! (constantly 54321)
                    pdb/get-conversation (constantly {:conversation/messages []})
                    pdb/update-msg! (constantly nil)
                    pdb/put-msg-SCR! (constantly nil)
                    pdb/ASCR-exists? (constantly true)
                    pdb/init-ASCR! (constantly nil)
                    pdb/put-ASCR! (constantly nil)
                    pdb/reduce-questioning-budget! (constantly nil)
                    pdb/mark-ASCR-complete! (constantly nil)
                    pdb/get-questioning-budget-left! (constantly 7)
                    llm/init-llm! (constantly nil)
                    llm/agent-prompts (atom {:some "value"})
                    llm/ds-interpret-prompt (constantly "mock prompt")
                    llm/complete-json (constantly {:materials ["aluminum" "steel"]})
                    sched-mcp.tools.orch.ds-util/ds-combine
                    (fn [_ new-scr _] new-scr)
                    sched-mcp.tools.orch.ds-util/ds-complete?
                    (constantly false)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "process/warm-up"
                       :answer "We use aluminum and steel"
                       :question-asked "What materials?"})]
          (is (map? result))
          (is (contains? result :scr))
          (is (= 54321 (:message_id result)))
          (is (true? (:ascr_updated result)))
          (is (false? (:ds_complete result)))
          (is (= 7 (:budget_remaining result))))))

    (testing "execute-tool with DS not found"
      (with-redefs [sdb/get-discovery-schema-JSON (constantly nil)]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"
                       :ds-id "nonexistent"
                       :answer "test"
                       :question-asked "test?"})]
          (is (contains? result :error))
          (is (re-find #"not found" (:error result))))))

    (testing "format-results"
      (let [success-result {:scr {:materials ["aluminum"]}
                            :message_id 12345
                            :ascr_updated true
                            :ds_complete false
                            :budget_remaining 5}
            error-result {:error "Interpretation failed"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "response-interpreted" (:message-type parsed)))
            (is (= 12345 (:message_id parsed)))))

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (re-find #"Interpretation failed" (first (:result formatted)))))))))

;;; Tests for get-current-ds tool

(deftest get-current-ds-tool-tests
  (let [tool-config (sut/create-get-current-ds-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :get-current-ds (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "sys_get_current_ds" (tool-system/tool-name tool-config))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= ["project_id" "conversation_id"] (:required schema)))))

    (testing "execute-tool with active DS"
      (with-redefs [pdb/get-current-DS (constantly :process/warm-up)
                    pdb/get-ASCR (constantly {:ascr/completed? false
                                              :ascr/dstruct mock-ascr})]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"})]
          ;; Note: The code uses (name ds-id) here too
          (is (= "warm-up" (:ds_id result)))
          (is (= :incomplete (:pursuit_status result))))))

    (testing "execute-tool with no active DS"
      (with-redefs [pdb/get-current-DS (constantly nil)
                    pdb/get-ASCR (constantly nil)]
        (let [result (tool-system/execute-tool
                      tool-config
                      {:project-id "proj-123"
                       :conversation-id "conv-456"})]
          (is (contains? result :error))
          (is (re-find #"No active DS" (:error result))))))

    (testing "format-results"
      (let [success-result {:ds_id "process/test"
                            :ds_template {:some "template"}
                            :interview_objective "Test objective"
                            :current_ascr {:some "data"}
                            :pursuit_status :incomplete}]
        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "current-ds-status" (:message-type parsed)))))))))

;;; Test for create-interviewer-tools

(deftest create-interviewer-tools-test
  (testing "creates all three interviewer tools"
    (let [tools (sut/create-interviewer-tools mock-system-atom)]
      (is (= 3 (count tools)))
      (is (= #{:formulate-question :interpret-response :get-current-ds}
             (set (map :tool-type tools))))
      (is (every? #(= mock-system-atom (:system-atom %)) tools)))))