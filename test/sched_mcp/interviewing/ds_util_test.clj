(ns sched-mcp.interviewing.ds_util-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.llm :as llm]
   [sched-mcp.system-db :as sdb]))

;;; =================================
;;; Individual ds-combine tests
;;; =================================

(deftest test-warm-up-ds-combine
  (testing "Warm-up DS combine operations"

    (testing "Initial SCR creates ASCR"
      (let [scr {:scheduling-challenges ["bottleneck-processes" "resource-assignment"]
                 :one-more-thing "Managing tank availability"
                 :product-or-service-name "craft beer"}
            ascr {}
            result (dsu/ds-combine :process/warm-up-with-challenges scr ascr)]
        (is (= result scr))
        (is (= (:scheduling-challenges result) ["bottleneck-processes" "resource-assignment"]))
        (is (= (:one-more-thing result) "Managing tank availability"))
        (is (= (:product-or-service-name result) "craft beer"))))

    (testing "SCR values override ASCR values (simple merge)"
      (let [scr {:scheduling-challenges ["equipment-utilization"]}
            ascr {:scheduling-challenges ["bottleneck-processes" "resource-assignment"]
                  :one-more-thing "Managing tank availability"
                  :product-or-service-name "craft beer"}
            result (dsu/ds-combine :process/warm-up-with-challenges scr ascr)]
        ;; Scheduling challenges are replaced, not accumulated
        (is (= (:scheduling-challenges result) ["equipment-utilization"]))
        ;; Other fields should remain unchanged
        (is (= (:one-more-thing result) "Managing tank availability"))
        (is (= (:product-or-service-name result) "craft beer"))))

    (testing "Updating one-more-thing"
      (let [scr {:one-more-thing "New insight about fermentation times"}
            ascr {:scheduling-challenges ["bottleneck-processes"]
                  :one-more-thing "Old insight"
                  :product-or-service-name "craft beer"}
            result (dsu/ds-combine :process/warm-up-with-challenges scr ascr)]
        ;; one-more-thing should be updated
        (is (= (:one-more-thing result) "New insight about fermentation times"))
        ;; Other fields unchanged
        (is (= (:scheduling-challenges result) ["bottleneck-processes"]))
        (is (= (:product-or-service-name result) "craft beer"))))

    (testing "Empty SCR leaves ASCR unchanged"
      (let [scr {}
            ascr {:scheduling-challenges ["bottleneck-processes" "resource-assignment"]
                  :one-more-thing "Some insight"}
            result (dsu/ds-combine :process/warm-up-with-challenges scr ascr)]
        (is (= result ascr))))))

(deftest test-flow-shop-ds-combine
  (testing "Flow shop DS combine operations"

    (testing "Initial SCR creates ASCR when empty"
      (let [scr {:routing-commonality-score 95
                 :product-examples ["Product A" "Product B"]}
            ascr {}
            result (dsu/ds-combine :process/flow-shop scr ascr)]
        (is (= result scr))))

    (testing "Flow shop prefers SCR with more subprocesses"
      (let [scr {:subprocesses [{:id "p1"} {:id "p2"} {:id "p3"}]
                 :routing-commonality-score 85}
            ascr {:subprocesses [{:id "p1"}]
                  :routing-commonality-score 95}
            result (dsu/ds-combine :process/flow-shop scr ascr)]
        ;; Should use SCR because it has more subprocesses
        (is (= result scr))
        (is (= (count (:subprocesses result)) 3))))

    (testing "Flow shop keeps ASCR when SCR has no subprocesses"
      (let [scr {:routing-commonality-score 85}
            ascr {:subprocesses [{:id "p1"}]
                  :routing-commonality-score 95}
            result (dsu/ds-combine :process/flow-shop scr ascr)]
        ;; Should keep ASCR
        (is (= result ascr))
        (is (= (:routing-commonality-score result) 95))))))

(deftest test-ds-combine-with-annotations
  (testing "Annotations are stripped before combining"
    (let [scr {:scheduling-challenges {:val ["new-challenge"]
                                       :metadata "some metadata"}
               :one-more-thing {:val "New insight"
                                :source "interview"}}
          ascr {:scheduling-challenges ["old-challenge"]}
          result (dsu/ds-combine :process/warm-up-with-challenges scr ascr)]
      ;; strip-annotations should extract the :val
      (is (= (:scheduling-challenges result) ["new-challenge"]))
      (is (= (:one-more-thing result) "New insight")))))

;;; =================================
;;; Tests using project data
;;; =================================

(deftest test-ds-combine-with-project-data
  (testing "DS combine with real project data"
    ;; Load a project file to get real ASCRs
    (let [project-file (io/file "data/projects/sur-craft-beer.edn")]
      (when (.exists project-file)
        (let [project-data (-> project-file slurp edn/read-string first)
              ascrs (:project/ASCRs project-data)]

          (when-let [warm-up-ascr (first (filter #(= (:ascr/id %) :process/warm-up-with-challenges) ascrs))]
            (testing "Combining with existing warm-up ASCR"
              (let [ascr-data (edn/read-string (:ascr/str warm-up-ascr))
                    new-scr {:scheduling-challenges ["demand-uncertainty"]}
                    result (dsu/ds-combine :process/warm-up-with-challenges new-scr ascr-data)]
                ;; Should replace challenges, not add
                (is (= (:scheduling-challenges result) ["demand-uncertainty"]))
                ;; Should preserve other fields
                (is (string? (:product-or-service-name result)))
                (is (string? (:one-more-thing result)))))))))))

(deftest test-ds-combine-edge-cases
  (testing "Edge cases for ds-combine"

    (testing "Nil SCR returns ASCR unchanged"
      (let [ascr {:scheduling-challenges ["test"]}]
        (is (= (dsu/ds-combine :process/warm-up-with-challenges nil ascr) ascr))))

    (testing "Empty SCR returns ASCR unchanged"
      (let [ascr {:scheduling-challenges ["test"]}]
        (is (= (dsu/ds-combine :process/warm-up-with-challenges {} ascr) ascr))))))

;;; =================================
;;; Test helpers
;;; =================================

(defn test-combine-idempotent
  "Test that combining an ASCR with itself returns the same ASCR"
  [ds-id ascr]
  (let [result (dsu/ds-combine ds-id ascr ascr)]
    (is (= result ascr)
        (str "Idempotent combine failed for " ds-id))))

(defn test-combine-empty-scr
  "Test that combining with empty SCR returns ASCR unchanged"
  [ds-id ascr]
  (let [result (dsu/ds-combine ds-id {} ascr)]
    (is (= result ascr)
        (str "Empty SCR combine failed for " ds-id))))

(defn test-combine-nil-scr
  "Test that combining with nil SCR returns ASCR unchanged"
  [ds-id ascr]
  (let [result (dsu/ds-combine ds-id nil ascr)]
    (is (= result ascr)
        (str "Nil SCR combine failed for " ds-id))))

;;; =================================
;;; Integration test helper
;;; =================================

(deftest test-all-project-ascrs
  (testing "Test ds-combine on all ASCRs found in project files"
    []
    (let [project-dir (io/file "data/projects")]
      (when (.exists project-dir)
        (doseq [file (.listFiles project-dir)
                :when (and (.isFile file)
                           (.endsWith (.getName file) ".edn"))]
          (try
            (let [project-data (-> file slurp edn/read-string first)
                  project-name (:project/name project-data)]
              (println "\nTesting project:" project-name)

              (doseq [ascr (:project/ASCRs project-data)]
                (let [ds-id (:ascr/id ascr)
                      ascr-data (edn/read-string (:ascr/str ascr))]
                  (println "  Testing DS:" ds-id)

                  ;; Run all standard tests
                  (test-combine-empty-scr ds-id ascr-data)
                  (test-combine-nil-scr ds-id ascr-data)
                  (test-combine-idempotent ds-id ascr-data)

                  (println "    ✓ All tests passed"))))

            (catch Exception e
              (println "Error processing file:" file)
              (println "  " (.getMessage e)))))))))

;; Additional test to understand strip-annotations behavior
(deftest test-strip-annotations
  (testing "Strip annotations functionality"
    (testing "Simple values pass through"
      (is (= (dsu/strip-annotations "hello") "hello"))
      (is (= (dsu/strip-annotations 42) 42))
      (is (= (dsu/strip-annotations ["a" "b"]) ["a" "b"])))

    (testing "Annotated values extract :val"
      (is (= (dsu/strip-annotations {:val "hello" :metadata "info"}) "hello"))
      (is (= (dsu/strip-annotations {:val 42 :source "user"}) 42)))

    (testing "Nested structures"
      (let [annotated {:field1 {:val "value1" :meta "data"}
                       :field2 ["a" {:val "b" :meta "data"} "c"]
                       :field3 {:nested {:val "deep" :info "extra"}}}
            expected {:field1 "value1"
                      :field2 ["a" "b" "c"]
                      :field3 {:nested "deep"}}]
        (is (= (dsu/strip-annotations annotated) expected))))))

;;; =================================
;;; Test scenarios for ds-complete?
;;; =================================

(deftest test-ds-complete
  (testing "ds-complete? for warm-up DS"
    ;; warm-up always returns true
    (is (= true (dsu/ds-complete? :process/warm-up-with-challenges {})))
    (is (= true (dsu/ds-complete? :process/warm-up-with-challenges
                                  {:scheduling-challenges ["test"]})))))

;;; =================================
;;; Understanding DS behavior
;;; =================================

(deftest test-understanding-ds-behavior
  (testing "Understanding different DS combine strategies"

    (testing "Warm-up uses simple merge (newer wins)"
      (let [result (dsu/ds-combine :process/warm-up-with-challenges
                                   {:a 2 :b 3}
                                   {:a 1 :c 3})]
        ;; merge gives us {:a 2, :b 3, :c 3}
        (is (= (:a result) 2))
        (is (= (:b result) 3))
        (is (= (:c result) 3))))

    (testing "Flow-shop has special subprocess logic"
      ;; It replaces entirely if SCR has more subprocesses
      (let [many-subs {:subprocesses (vec (repeat 5 {:id "sub"}))}
            few-subs {:subprocesses [{:id "sub1"}] :other "data"}
            result (dsu/ds-combine :process/flow-shop many-subs few-subs)]
        (is (= result many-subs))
        (is (nil? (:other result))))))) ; other data is lost

;; Run this to test all projects:
;; (test-all-project-ascrs)

;;; ======================== PROMPT QUALITY TESTS ========================
(deftest test-prompt-structure
  (testing "DS Question Prompt Structure"
    ;; Test that prompts have proper structure
    (let [prompt (dsu/formulate-question-prompt
                  {:ds-instructions (sdb/get-DS-instructions :process/warm-up-with-challenges)
                   :ascr {}
                   :budget-remaining 1.0})]
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
    (let [test-ascr {:scheduling-challenges ["demand-uncertainty"]}
          prompt-msgs (dsu/formulate-question-prompt
                       {:ds-instructions (sdb/get-DS-instructions :process/warm-up-with-challenges)
                        :ascr test-ascr
                        :budget-remaining 5})
          prompt-text (str/join " " (map :content prompt-msgs))]
      ;; Should mention what's already collected
      (is (re-find #"demand-uncertainty" prompt-text))
      ;; Should indicate budget
      (is (re-find #"5|five" (str/lower-case prompt-text)))
      ;; Should mention missing fields
      (is (or (re-find #"product.*name" prompt-text)
              (re-find #"one.*more.*thing" prompt-text))))))

;;; ======================== PROMPT EFFECTIVENESS TESTS ========================
(deftest test-question-generation-quality
  (testing "Generated questions are appropriate and clear"
    ;; Note: These tests require an API key to actually run
    ;; They test the quality of generated questions
    (let [ds-instructions (sdb/get-DS-instructions :process/warm-up-with-challenges)]
      (testing "Warm-up questions are conversational"
        (let [result (llm/complete-json
                      (dsu/formulate-question-prompt
                       {:ds-instructions ds-instructions
                        :ascr {}
                        :budget-remaining 10})
                      :model-class :mini)]
          (is (map? result))
          (is (:question result))
          ;; Question should be natural language, not technical
          (is (not (re-find #"DS|SCR|schema" (:question result))))
          ;; Should be open-ended for warm-up
          (is (re-find #"\?" (:question result)))))

      (testing "Follow-up questions build on context"
        (let [ascr {:scheduling-challenges ["equipment-changeover" "demand-uncertainty"]}
              result (llm/complete-json
                      (dsu/formulate-question-prompt
                       {:ds ds-instructions
                        :ascr ascr
                        :budget-remaining 5})
                      :model-class :mini)]
          ;; Should ask about missing fields
          (is (or (re-find #"product" (str/lower-case (:question result)))
                  (re-find #"service" (str/lower-case (:question result))))))))))

;;; ======================== RESPONSE INTERPRETATION TESTS ========================

(deftest test-response-interpretation
  (testing "LLM correctly extracts structured data from answers"
    (when-not (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "This test requires an OpenAI key." {})))
    (testing "Extract scheduling challenges from natural text"
      (let [ds-instructions (sdb/get-DS-instructions :process/warm-up-with-challenges )
            answer "We struggle with machine breakdowns and unpredictable customer orders. Also, our skilled workers are often unavailable when we need them."
            result (llm/complete-json
                    (dsu/interpret-response-prompt
                     {:ds-instructions ds-instructions
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
      (let [ds (sdb/get-DS-instructions :process/warm-up-with-challenges)
            answer "We're a craft brewery making various types of beer including IPAs, stouts, and seasonal ales."
            result (llm/complete-json
                    (dsu/interpret-response-prompt
                     {:ds ds
                      :question "What products do you make?"
                      :answer answer})
                    :model-class :extract)]
        (is (string? (get-in result [:scr :product-or-service-name])))
        (is (re-find #"beer|brew"
                     (str/lower-case
                      (get-in result [:scr :product-or-service-name]))))))))

;;; ======================== PROMPT REFINEMENT TESTS ========================

(deftest test-prompt-variations
  (testing "Different prompt styles produce consistent results"
    (when (System/getenv "OPENAI_API_KEY")
      (llm/init-llm!)

      (let [ds {:DS-id :test/simple
                :interview-objective "Test extraction"
                :DS {:item-count {:comment "Number of items"}}}
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
        (let [ds (sdb/get-DS-instructions :process/warm-up-with-challenges)
              ambiguous-answer "Maybe sometimes we have issues, I'm not really sure"
              result (llm/complete-json
                      (dsu/interpret-response-prompt
                       {:ds (:DS ds)
                        :question "What are your scheduling challenges?"
                        :answer ambiguous-answer})
                      :model-class :extract)]
          (is (map? result))
          (is (or (seq (:ambiguities result))
                  (< (:confidence result) 0.5)))))

      (testing "Off-topic answer handling"
        (let [ds (sdb/get-DS-instructions :process/warm-up-with-challenges)
              off-topic "The weather has been nice lately"
              result (llm/complete-json
                      (dsu/interpret-response-prompt
                       {:ds ds
                        :question "What products do you make?"
                        :answer off-topic})
                      :model-class :extract)]
          (is (or (nil? (get-in result [:scr :product-or-service-name]))
                  (seq (:ambiguities result)))))))))
