(ns sched-mcp.llm-test
  "Test LLM integration"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.llm :as llm]))

(deftest test-llm-basics
  (testing "Basic LLM functions"
    ;; Test message creation
    (is (= {:role "system" :content "Hello"}
           (llm/system-message "Hello")))

    ;; Test prompt building
    (let [prompt (llm/build-prompt
                  :system "You are helpful"
                  :user "What is 2+2?")]
      (is (= 2 (count prompt)))
      (is (= "system" (:role (first prompt))))
      (is (= "user" (:role (second prompt))))))

  (testing "Model selection"
    (is (string? (llm/pick-model :chat :openai)))
    (is (thrown? Exception (llm/pick-model :invalid :openai)))))

(deftest test-agent-prompts
  (testing "Agent prompt loading"
    ;; This should work even without the files
    (try
      (llm/init-llm!)
      (catch Exception e
        ;; Expected if no API key
        (is (re-find #"No API credentials" (.getMessage e)))))))

(defn run-tests []
  (clojure.test/run-tests 'sched-mcp.llm-test))

;; Manual LLM test (requires API key)
(comment
  ;; Set API key first:
  ;; export OPENAI_API_KEY="your-key"

  ;; Then test:
  (llm/complete [(llm/system-message "You are helpful")
                 (llm/user-message "Say hello in 5 words")])

  ;; Test JSON
  (llm/complete-json
   [(llm/system-message "Extract entities")
    (llm/user-message "We make beer in 3 fermentation tanks")]
   :model-class :mini))
