(ns sched-mcp.tools.surrogate.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.data.json :as json]
   [sched-mcp.tools.surrogate.core :as sut]
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.sutil :as sutil]))

;;; Test fixtures and mock data

;; Mock the database connections
(defn setup-mock-databases []
  ;; Initialize the project database atom to prevent connection attempts
  (swap! sutil/databases-atm assoc :proj-123 {:mock "project-db"})
  (swap! sutil/databases-atm assoc :craft-beer-surrogate-123 {:mock "surrogate-project-db"}))

(defn teardown-mock-databases []
  ;; Clean up the mock databases
  (swap! sutil/databases-atm dissoc :proj-123 :craft-beer-surrogate-123))

(use-fixtures :each (fn [f]
                      (setup-mock-databases)
                      (f)
                      (teardown-mock-databases)))

(def mock-start-result
  {:status "success"
   :message "🟠 Started surrogate expert for craft-beer domain"
   :project-id "craft-beer-surrogate-123"
   :expert-id "expert-456"
   :domain :craft-beer})

(def mock-answer-result
  {:response "🟠 We use a 3-vessel brewing system with separate mash tun, lauter tun, and kettle. Our fermentation typically takes 10-14 days at controlled temperatures."
   :project-id "craft-beer-surrogate-123"})

(def mock-system-atom (atom {}))

;;; Tests for start-surrogate tool

(deftest start-surrogate-tool-tests
  (let [tool-config (sut/create-start-surrogate-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :start-surrogate (:tool-type tool-config)))
      (is (= mock-system-atom (:system-atom tool-config))))

    (testing "tool-name"
      (is (= "sur_start_expert" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (pos? (count desc)))
        (is (re-find #"surrogate expert agent" desc))
        (is (re-find #"domain expert" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :domain))
        (is (contains? (:properties schema) :company_name))
        (is (contains? (:properties schema) :project_name))
        (is (= ["domain"] (:required schema)))))

    (testing "validate-inputs with valid domain"
      (is (= {:domain "craft-beer"}
             (tool-system/validate-inputs
              tool-config
              {:domain "craft-beer"})))

      (is (= {:domain "plate-glass"
              :company-name "Test Company"
              :project-name "Test Project"}
             (tool-system/validate-inputs
              tool-config
              {:domain "plate-glass"
               :company-name "Test Company"
               :project-name "Test Project"}))))

    (testing "validate-inputs with missing domain"
      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs tool-config {})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs
                    tool-config
                    {:company-name "Test"}))))

    (testing "execute-tool with valid domain"
      (with-redefs [suru/start-surrogate-interview
                    (fn [{:keys [domain company-name project-name]}]
                      (merge mock-start-result
                             {:domain domain
                              :company-name company-name
                              :project-name project-name}))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "craft-beer"
                       :company_name "Mountain Peak Brewery"
                       :project_name "Craft Beer Scheduling"})]
          (is (map? result))
          (is (= "success" (:status result)))
          (is (= "craft-beer-surrogate-123" (:project_id result)))
          (is (= "expert-456" (:expert_id result)))
          (is (= "craft-beer" (:domain result))))))

    (testing "execute-tool without optional parameters"
      (with-redefs [suru/start-surrogate-interview
                    (constantly mock-start-result)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "plate-glass"})]
          (is (map? result))
          (is (= "success" (:status result))))))

    (testing "execute-tool with error"
      (with-redefs [suru/start-surrogate-interview
                    (fn [& _] (throw (Exception. "Failed to start expert")))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "craft-beer"})]
          (is (map? result))
          (is (= "error" (:status result)))
          (is (re-find #"Failed to start expert" (:message result))))))

    (testing "format-results with success"
      (let [success-result {:status "success"
                            :message "Started expert"
                            :project_id "craft-beer-surrogate-123"
                            :expert_id "expert-456"
                            :domain "craft-beer"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (is (vector? (:result formatted)))
          (is (= 1 (count (:result formatted))))
          (is (re-find #"Started surrogate expert for craft-beer"
                       (first (:result formatted))))
          (is (re-find #"Project ID: craft-beer-surrogate-123"
                       (first (:result formatted))))
          (is (re-find #"Expert ID: expert-456"
                       (first (:result formatted)))))))

    (testing "format-results with error"
      (let [error-result {:status "error"
                          :message "Failed to initialize expert"}]

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (= ["Failed to initialize expert"] (:result formatted))))))))

;;; Tests for answer-question tool

(deftest answer-question-tool-tests
  (let [tool-config (sut/create-answer-question-tool mock-system-atom)]

    (testing "tool creation"
      (is (= :answer-question (:tool-type tool-config)))
      (is (= mock-system-atom (:system-atom tool-config))))

    (testing "tool-name"
      (is (= "sur_answer" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (re-find #"surrogate expert" desc))
        (is (re-find #"domain expert" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :project_id))
        (is (contains? (:properties schema) :question))
        (is (= ["project_id" "question"] (:required schema)))))

    (testing "validate-inputs with valid inputs"
      (let [valid-inputs {:project-id "craft-beer-surrogate-123"
                          :question "What are your main processes?"}]
        (is (= valid-inputs
               (tool-system/validate-inputs tool-config valid-inputs)))))

    (testing "validate-inputs with missing parameters"
      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs tool-config {})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs
                    tool-config
                    {:project-id "test"})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs
                    tool-config
                    {:question "test?"}))))

    (testing "execute-tool with valid question"
      (with-redefs [suru/surrogate-answer-question
                    (fn [{:keys [project-id question]}]
                      (assoc mock-answer-result
                             :project-id project-id
                             :question question))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project_id "craft-beer-surrogate-123"
                       :question "What is your brewing process?"})]
          (is (map? result))
          (is (= "success" (:status result)))
          (is (contains? result :expert_response))
          (is (re-find #"3-vessel brewing system" (:expert_response result)))
          (is (= "craft-beer-surrogate-123" (:project_id result))))))

    (testing "execute-tool with expert session error"
      (with-redefs [suru/surrogate-answer-question
                    (constantly {:error "No expert session found for project"})]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project_id "nonexistent-project"
                       :question "test question"})]
          (is (map? result))
          (is (= "error" (:status result)))
          (is (re-find #"No expert session found" (:message result))))))

    (testing "execute-tool with exception"
      (with-redefs [suru/surrogate-answer-question
                    (fn [& _] (throw (Exception. "LLM connection failed")))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:project_id "test-123"
                       :question "test?"})]
          (is (map? result))
          (is (= "error" (:status result)))
          (is (re-find #"LLM connection failed" (:message result))))))

    (testing "format-results with success"
      (let [success-result {:status "success"
                            :expert_response "We brew IPA, Stout, and Lager varieties."
                            :project_id "craft-beer-surrogate-123"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (is (vector? (:result formatted)))
          (is (= 1 (count (:result formatted))))
          (let [parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
            (is (= "surrogate-response" (:message-type parsed)))
            (is (= "We brew IPA, Stout, and Lager varieties."
                   (:expert_response parsed)))
            (is (= "craft-beer-surrogate-123" (:project_id parsed)))))))

    (testing "format-results with error"
      (let [error-result {:status "error"
                          :message "Expert not available"}]

        (let [formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (= ["Error: Expert not available"] (:result formatted))))))))

;;; Test for create-sur-tools

(deftest create-sur-tools-test
  (testing "creates both surrogate tools"
    (let [tools (sut/create-sur-tools mock-system-atom)]
      (is (= 2 (count tools)))
      (is (= #{:start-surrogate :answer-question}
             (set (map :tool-type tools))))
      (is (every? #(= mock-system-atom (:system-atom %)) tools))))

  (testing "tools can be registered"
    (let [tools (sut/create-sur-tools mock-system-atom)
          registered (mapv tool-system/registration-map tools)]
      (is (= 2 (count registered)))
      (is (= #{"sur_start_expert" "sur_answer"}
             (set (map :name registered))))
      (is (every? :tool-fn registered))
      (is (every? :schema registered))
      (is (every? :description registered)))))

;;; Integration tests

(deftest surrogate-tools-integration-test
  (testing "complete workflow: start expert and ask question"
    (with-redefs [suru/start-surrogate-interview
                  (constantly mock-start-result)
                  suru/surrogate-answer-question
                  (constantly mock-answer-result)]

      (let [start-tool (sut/create-start-surrogate-tool mock-system-atom)
            answer-tool (sut/create-answer-question-tool mock-system-atom)]

        ;; Start the expert
        (let [start-result (tool-system/execute-tool
                            start-tool
                            {:domain "craft-beer"
                             :company_name "Test Brewery"
                             :project_name "Test Project"})]
          (is (= "success" (:status start-result)))
          (is (some? (:project_id start-result)))

          ;; Ask a question using the project ID
          (let [answer-result (tool-system/execute-tool
                               answer-tool
                               {:project_id (:project_id start-result)
                                :question "Describe your fermentation process?"})]
            (is (= "success" (:status answer-result)))
            (is (some? (:expert_response answer-result)))
            (is (= (:project_id start-result)
                   (:project_id answer-result)))))))))

;;; Edge case tests

(deftest surrogate-tools-edge-cases
  (testing "start-surrogate with various domains"
    (with-redefs [suru/start-surrogate-interview
                  (fn [{:keys [domain]}]
                    (assoc mock-start-result :domain domain))]

      (let [tool-config (sut/create-start-surrogate-tool mock-system-atom)]
        (doseq [domain ["craft-beer" "plate-glass" "metal-fabrication"
                        "textiles" "semiconductor" "pharmaceuticals"]]
          (let [result (tool-system/execute-tool
                        tool-config
                        {:domain domain})]
            (is (= "success" (:status result)))
            (is (= domain (:domain result))))))))

  (testing "answer-question with long responses"
    (with-redefs [suru/surrogate-answer-question
                  (constantly {:response (apply str (repeat 1000 "test "))
                               :project-id "test-123"})]

      (let [tool-config (sut/create-answer-question-tool mock-system-atom)
            result (tool-system/execute-tool
                    tool-config
                    {:project_id "test-123"
                     :question "Explain your entire process in detail?"})]
        (is (= "success" (:status result)))
        (is (> (count (:expert_response result)) 1000)))))

  (testing "answer-question with special characters"
    (with-redefs [suru/surrogate-answer-question
                  (constantly {:response "We use <html> tags, \"quotes\", and 'apostrophes'"
                               :project-id "test-123"})]

      (let [tool-config (sut/create-answer-question-tool mock-system-atom)
            result (tool-system/execute-tool
                    tool-config
                    {:project_id "test-123"
                     :question "test"})]
        (is (= "success" (:status result)))
        (is (string? (:expert_response result)))
        ;; Verify JSON formatting handles special characters
        (let [formatted (tool-system/format-results tool-config result)
              parsed (json/read-str (first (:result formatted)) :key-fn keyword)]
          (is (= (:expert_response result)
                 (:expert_response parsed))))))))
