(ns sched-mcp.llm-prompt-test
  "Test LLM prompts for quality and effectiveness
   Week 3 Day 3: Testing and Prompt Refinement"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [sched-mcp.llm-direct :as llm]
   [sched-mcp.ds-loader :as ds]
   [sched-mcp.util :refer [alog!]]))

;;; ======================== PROMPT QUALITY TESTS ========================

(deftest test-prompt-structure
  (testing "DS Question Prompt Structure"
    ;; Test that prompts have proper structure
    (let [test-ds {:eads-id :process/warm-up-with-challenges
                   :interview-objective "Understand scheduling challenges"
                   :eads {:scheduling-challenges {:comment "List of challenges"}
                          :product-or-service-name {:comment "Name of product"}}}
          test-ascr {}
          prompt (llm/ds-question-prompt
                  {:ds test-ds
                   :ascr test-ascr
                   :budget-remaining 10})]
      ;; Check message structure
      (is (vector? prompt))
      (is (= 2 (count prompt))) ; System + User
      (is (= "system" (:role (first prompt))))
      (is (= "user" (:role (second prompt))))

      ;; Check content
      (let [system-content (:content (first prompt))
            user-content (:content (second prompt))]
        (is (string? system-content))
        (is (string? user-content))
        ;; System message should contain interviewer instructions
        (is (re-find #"interview" (str/lower-case system-content)))
        ;; User message should contain DS info
        (is (re-find #"warm-up-with-challenges" user-content))))))

(deftest test-prompt-completeness
  (testing "Prompts include all necessary context"
    (let [test-ds {:eads-id :process/warm-up-with-challenges
                   :interview-objective "Understand challenges"
                   :eads {:scheduling-challenges {:comment "List challenges"}
                          :product-or-service-name {:comment "Product name"}
                          :one-more-thing {:comment "Additional observation"}}}
          test-ascr {:scheduling-challenges ["demand-uncertainty"]}
          prompt-msgs (llm/ds-question-prompt
                       {:ds test-ds
                        :ascr test-ascr
                        :budget-remaining 5})]
      (let [prompt-text (str/join " " (map :content prompt-msgs))]
        ;; Should mention what's already collected
        (is (re-find #"demand-uncertainty" prompt-text))
        ;; Should indicate budget
        (is (re-find #"5|five" (str/lower-case prompt-text)))
        ;; Should mention missing fields
        (is (or (re-find #"product.*name" prompt-text)
                (re-find #"one.*more.*thing" prompt-text)))))))

;;; ======================== PROMPT EFFECTIVENESS TESTS ========================

(deftest test-question-generation-quality
  (testing "Generated questions are appropriate and clear"
    ;; Note: These tests require an API key to actually run
    ;; They test the quality of generated questions
    (when (System/getenv "OPENAI_API_KEY")
      (llm/init-llm!)

      (testing "Warm-up questions are conversational"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              result (llm/complete-json
                      (llm/ds-question-prompt
                       {:ds ds
                        :ascr {}
                        :budget-remaining 10})
                      :model-class :mini)]
          (is (map? result))
          (is (:question result))
          ;; Question should be natural language, not technical
          (is (not (re-find #"EADS|SCR|schema" (:question result))))
          ;; Should be open-ended for warm-up
          (is (re-find #"\?" (:question result)))))

      (testing "Follow-up questions build on context"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              ascr {:scheduling-challenges ["equipment-changeover" "demand-uncertainty"]}
              result (llm/complete-json
                      (llm/ds-question-prompt
                       {:ds ds
                        :ascr ascr
                        :budget-remaining 5})
                      :model-class :mini)]
          ;; Should ask about missing fields
          (is (or (re-find #"product" (str/lower-case (:question result)))
                  (re-find #"service" (str/lower-case (:question result))))))))))

;;; ======================== RESPONSE INTERPRETATION TESTS ========================

(deftest test-response-interpretation
  (testing "LLM correctly extracts structured data from answers"
    (when (System/getenv "OPENAI_API_KEY")
      (llm/init-llm!)

      (testing "Extract scheduling challenges from natural text"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              answer "We struggle with machine breakdowns and unpredictable customer orders. Also, our skilled workers are often unavailable when we need them."
              result (llm/complete-json
                      (llm/ds-interpret-prompt
                       {:ds ds
                        :question "What are your main scheduling challenges?"
                        :answer answer})
                      :model-class :extract)]
          (is (map? result))
          (is (map? (:scr result)))
          (let [challenges (get-in result [:scr :scheduling-challenges])]
            (is (vector? challenges))
            (is (>= (count challenges) 2))
            ;; Should identify key challenges
            (is (some #(re-find #"demand" %) challenges))
            (is (some #(re-find #"worker|skill" %) challenges)))))

      (testing "Extract product/service name"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              answer "We're a craft brewery making various types of beer including IPAs, stouts, and seasonal ales."
              result (llm/complete-json
                      (llm/ds-interpret-prompt
                       {:ds ds
                        :question "What products do you make?"
                        :answer answer})
                      :model-class :extract)]
          (is (string? (get-in result [:scr :product-or-service-name])))
          (is (re-find #"beer|brew"
                       (str/lower-case
                        (get-in result [:scr :product-or-service-name])))))))))

;;; ======================== PROMPT REFINEMENT TESTS ========================

(deftest test-prompt-variations
  (testing "Different prompt styles produce consistent results"
    (when (System/getenv "OPENAI_API_KEY")
      (llm/init-llm!)

      (let [ds {:eads-id :test/simple
                :interview-objective "Test extraction"
                :eads {:item-count {:comment "Number of items"}}}
            answer "We have three production lines"

            ;; Test different prompt styles
            direct-prompt [(llm/system-message "Extract the number of production lines")
                           (llm/user-message answer)]

            detailed-prompt [(llm/system-message "You are analyzing manufacturing data. Extract structured information.")
                             (llm/user-message (str "Answer: " answer "\nExtract: item-count"))]

            result1 (llm/complete-json direct-prompt :model-class :mini)
            result2 (llm/complete-json detailed-prompt :model-class :mini)]

        ;; Both should extract "3" or "three"
        (is (or (= 3 (:item-count result1))
                (= "three" (:item-count result1))
                (= "3" (:item-count result1))))
        (is (or (= 3 (:item-count result2))
                (= "three" (:item-count result2))
                (= "3" (:item-count result2))))))))

;;; ======================== ERROR HANDLING TESTS ========================

(deftest test-prompt-error-handling
  (testing "System handles ambiguous or problematic responses gracefully"
    (when (System/getenv "OPENAI_API_KEY")
      (llm/init-llm!)

      (testing "Ambiguous answer detection"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              ambiguous-answer "Maybe sometimes we have issues, I'm not really sure"
              result (llm/complete-json
                      (llm/ds-interpret-prompt
                       {:ds ds
                        :question "What are your scheduling challenges?"
                        :answer ambiguous-answer})
                      :model-class :extract)]
          (is (map? result))
          (is (or (seq (:ambiguities result))
                  (< (:confidence result) 0.5)))))

      (testing "Off-topic answer handling"
        (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)
              off-topic "The weather has been nice lately"
              result (llm/complete-json
                      (llm/ds-interpret-prompt
                       {:ds ds
                        :question "What products do you make?"
                        :answer off-topic})
                      :model-class :extract)]
          (is (or (nil? (get-in result [:scr :product-or-service-name]))
                  (seq (:ambiguities result)))))))))

;;; ======================== PROMPT TEMPLATE TESTS ========================

(deftest test-agent-prompt-templates
  (testing "Agent prompt templates are well-formed"
    ;; Initialize to load templates
    (llm/init-llm!)

    (testing "All expected agent prompts are loaded"
      (is (contains? @llm/agent-prompts :ds-question))
      (is (contains? @llm/agent-prompts :ds-interpret)))

    (testing "Prompt templates have required structure"
      (let [question-prompt (llm/get-agent-prompt :ds-question)]
        (is (string? question-prompt))
        (is (> (count question-prompt) 100)) ; Non-trivial prompt
        ;; Should contain placeholders
        (is (re-find #"\{\{" question-prompt)))

      (let [interpret-prompt (llm/get-agent-prompt :ds-interpret)]
        (is (string? interpret-prompt))
        (is (> (count interpret-prompt) 100))))))

;;; ======================== RUN ALL TESTS ========================

(defn run-tests []
  (clojure.test/run-tests 'sched-mcp.llm-prompt-test))

;; For interactive testing with API key:
(comment
  (run-tests)

  ;; Test a specific prompt manually
  (do
    (llm/init-llm!)
    (let [ds (ds/get-cached-ds :process/warm-up-with-challenges)]
      (llm/complete-json
       (llm/ds-question-prompt
        {:ds ds
         :ascr {:scheduling-challenges ["demand-uncertainty"]}
         :budget-remaining 8})
       :model-class :mini))))
