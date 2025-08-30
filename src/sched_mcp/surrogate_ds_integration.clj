(ns sched-mcp.surrogate-ds-integration
  "Integration between surrogate experts and Discovery Schema interview flow"
  (:require
   [clojure.pprint :refer [pprint]]
   [mount.core :as mount]
   [sched-mcp.surrogate :as surrogate]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.tools.registry :as registry]
   [sched-mcp.db :as db]
   [sched-mcp.util :as util :refer [log!]]))

;; Helper functions to call tools through the tool-system

(defn formulate-question
  "Call the formulate-question tool"
  [project-id conversation-id ds-id]
  (let [tool {:tool-type :formulate-question
              :system-atom registry/system-atom}
        inputs {:project-id project-id
                :conversation-id conversation-id
                :ds-id ds-id}]
    (tool-system/execute-tool tool inputs)))

(defn interpret-response
  "Call the interpret-response tool"
  [project-id conversation-id ds-id answer question-asked]
  (let [tool {:tool-type :interpret-response
              :system-atom registry/system-atom}
        inputs {:project-id project-id
                :conversation-id conversation-id
                :ds-id ds-id
                :answer answer
                :question-asked question-asked}]
    (tool-system/execute-tool tool inputs)))

(defn run-surrogate-with-ds
  "Run a Discovery Schema interview with a surrogate expert"
  [{:keys [domain company-name ds-id]
    :or {ds-id "process/warm-up-with-challenges"}}]

  ;; Start the surrogate expert
  (let [surrogate-session (surrogate/start-surrogate-interview
                           {:domain domain
                            :company-name company-name
                            :project-name (str company-name " Interview")})
        project-id (:project-id surrogate-session)
        conversation-id (:conversation-id surrogate-session)]

    (println "\n=== Starting DS Interview with Surrogate Expert ===")
    (println "🟠 Expert:" (:company-name surrogate-session))
    (println "Domain:" (name domain))
    (println "DS:" ds-id)
    (println "Project ID:" project-id)
    (println "Conversation ID:" conversation-id)
    (println)

    ;; The conversation already exists from create-project!, so just update its DS
    (db/update-conversation-state! project-id conversation-id
                                   {:conversation/current-ds (keyword ds-id)})

    ;; Interview loop
    (loop [round 1]
      (when (<= round 5) ; Limit rounds for testing
        (println (str "\n--- Round " round " ---"))

        ;; Formulate question using tool-system
        (let [question-result (formulate-question project-id conversation-id ds-id)]
          (if (:error question-result)
            (println "Error formulating question:" (:error question-result))
            (let [question-text (get-in question-result [:question :text])]
              (println "\n📋 Question:" question-text)

              ;; Get surrogate answer
              (let [answer-result (surrogate/surrogate-answer-question
                                   {:project-id project-id
                                    :question question-text})]
                (println "\n🟠 Expert Answer:" (:response answer-result))

                ;; Interpret response using tool-system
                (let [interpretation (interpret-response
                                      project-id conversation-id ds-id
                                      (:response answer-result)
                                      question-text)]
                  (println "\n🔍 Interpretation:")
                  (pprint (:scr interpretation))

                  ;; For now, just do a fixed number of rounds
                  ;; In a real system, the orchestrator would determine when to stop
                  (when (< round 5)
                    (recur (inc round))))))))))))

(defn test-warm-up-interview
  "Test the warm-up DS with a surrogate expert"
  [domain company-name]
  (mount/start)
  (run-surrogate-with-ds {:domain domain
                          :company-name company-name
                          :ds-id "process/warm-up-with-challenges"}))

(defn demo-fiberglass-doors
  "Demo interview for fiberglass garage doors"
  []
  (test-warm-up-interview :fiberglass-garage-doors
                          "Premier Garage Door Manufacturing"))

(defn demo-craft-beer
  "Demo interview for craft beer"
  []
  (test-warm-up-interview :craft-beer
                          "Mountain Peak Brewery"))

;; Usage examples
(comment
  ;; Test fiberglass doors
  (demo-fiberglass-doors)

  ;; Test craft beer
  (demo-craft-beer)

  ;; Test any domain
  (test-warm-up-interview :semiconductor-fab "ChipCo Industries"))
