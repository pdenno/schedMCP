(ns sched-mcp.surrogate-test
  "Test the surrogate expert functionality"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.tools.surrogate.sur-util :as sur]))

(deftest test-surrogate-interview-flow
  (testing "Starting and conducting a surrogate interview"
    (let [response (sur/surrogate-answer-question
                    {:project-id (:project-id session-info)
                     :question "What are your main brewing processes?"})]
      (is (contains? response :response))))

(defn test-craft-beer-expert
  "Manual test for craft beer expert"
  []
  (let [pid (-> (sur/start-surrogate-interview
                 {:domain :craft-beer
                  :project-name "Craft Beer Scheduling"})
                :project-id
                keyword)]
  ;; Ask some questions
    (let [q1 "What are the main steps in your beer production process?"
          a1 (sur/surrogate-answer-question pid q1)]

    (println "\n🟠 Q:" q1)
    (println "🟠 A:" (:response a1)))

  ;; Ask about challenges
  (let [q2 "What scheduling challenges do you face?"
        a2 (sur/surrogate-answer-question pid q2)]

    (println "\n🟠 Q:" q2)
    (println "🟠 A:" (:response a2)))

  ;; Ask for a table
  (let [q3 "Can you provide a table showing your main fermentation tanks and their capacities?"
        a3 (sur/surrogate-answer-question pid q3)]

    (println "\n🟠 Q:" q3)
    (println "🟠 A:" (:response a3)))))


(defn test-plate-glass-expert
  "Manual test for plate glass expert"
  []
  (let [session (sur/start-surrogate-interview
                 {:domain :plate-glass
                  :company-name "ClearView Glass Manufacturing"
                  :project-name "Glass Production Scheduling"})]

    (println "\n=== Started Surrogate Expert Session ===")
    (println "Project ID:" (:project-id session))
    (println "Expert:" (:company-name session))

    ;; Ask about continuous process
    (let [q1 "How does your plate glass manufacturing process work?"
          a1 (sur/surrogate-answer-question
              {:project-id (:project-id session)
               :question q1})]

      (println "\n🟠 Q:" q1)
      (println "🟠 A:" (:response a1)))

    session))

(comment
  ;; Run manual tests
  (test-craft-beer-expert)
  (test-plate-glass-expert)

  ;; Check active sessions
  (sur/list-surrogate-sessions))
