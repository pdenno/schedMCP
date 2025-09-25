(ns sched-mcp.surrogate-test
  "Test the surrogate expert functionality"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.surrogate :as surrogate]
   [sched-mcp.test-helpers :as test-helpers]
   [mount.core :as mount]))

(deftest test-surrogate-creation
  (testing "Creating a surrogate expert persona"
    (mount/start)
    (let [persona (surrogate/create-expert-persona
                   {:domain :craft-beer
                    :company-name "Test Brewery"})]

      (is (= :craft-beer (:domain persona)))
      (is (= "Test Brewery" (:company-name persona)))
      (is (contains? persona :expert-id))
      (is (contains? persona :created-at))))
  (mount/stop))

(deftest test-surrogate-interview-flow
  (testing "Starting and conducting a surrogate interview"
    (mount/start)
    ;; Start interview
    (let [session-info (surrogate/start-surrogate-interview
                        {:domain :craft-beer
                         :company-name "Mountain Peak Brewery"
                         :project-name "Test Brewery Interview"})]

      (is (contains? session-info :project-id))
      (is (contains? session-info :expert-id))
      (is (= :craft-beer (:domain session-info)))

      ;; Ask a question
      (let [response (surrogate/surrogate-answer-question
                      {:project-id (:project-id session-info)
                       :question "What are your main brewing processes?"})]

        (is (contains? response :response))
        (is (= "orange" (:display-color response)))

        ;; Check session was updated
        #_(let [session (surrogate/get-surrogate-session (:project-id session-info))]
          (is (= 1 (count (:conversation-history session))))))))
  (mount/stop))

;; Manual testing functions
(defn test-craft-beer-expert
  "Manual test for craft beer expert"
  []
  (mount/start)
  (let [session (surrogate/start-surrogate-interview
                 {:domain :craft-beer
                  :company-name "Rocky Mountain Craft Brewery"
                  :project-name "Craft Beer Scheduling"})]

    (println "\n=== Started Surrogate Expert Session ===")
    (println "Project ID:" (:project-id session))
    (println "Expert:" (:company-name session))

    ;; Ask some questions
    (let [q1 "What are the main steps in your beer production process?"
          a1 (surrogate/surrogate-answer-question
              {:project-id (:project-id session)
               :question q1})]

      (println "\n🟠 Q:" q1)
      (println "🟠 A:" (:response a1)))

    ;; Ask about challenges
    (let [q2 "What scheduling challenges do you face?"
          a2 (surrogate/surrogate-answer-question
              {:project-id (:project-id session)
               :question q2})]

      (println "\n🟠 Q:" q2)
      (println "🟠 A:" (:response a2)))

    ;; Ask for a table
    (let [q3 "Can you provide a table showing your main fermentation tanks and their capacities?"
          a3 (surrogate/surrogate-answer-question
              {:project-id (:project-id session)
               :question q3})]

      (println "\n🟠 Q:" q3)
      (println "🟠 A:" (:response a3)))

    session))

(defn test-plate-glass-expert
  "Manual test for plate glass expert"
  []
  (mount/start)
  (let [session (surrogate/start-surrogate-interview
                 {:domain :plate-glass
                  :company-name "ClearView Glass Manufacturing"
                  :project-name "Glass Production Scheduling"})]

    (println "\n=== Started Surrogate Expert Session ===")
    (println "Project ID:" (:project-id session))
    (println "Expert:" (:company-name session))

    ;; Ask about continuous process
    (let [q1 "How does your plate glass manufacturing process work?"
          a1 (surrogate/surrogate-answer-question
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
  (surrogate/list-surrogate-sessions))
