(ns sched-mcp.phase2-5-test
  "Test script for Phase 2.5 - Core DS Flow"
  (:require
   [clojure.edn :as edn]
   [sched-mcp.tools.registry :as registry]
   [sched-mcp.sutil :refer [connect-atm]]
   [datahike.api :as d]))

(defn test-ds-flow
  "Test the basic DS flow: warm-up → scheduling-problem-type"
  []
  (println "\n=== Phase 2.5 Test: Core DS Flow ===\n")

  ;; Get tools
  (let [tools-by-name (into {} (map (juxt :name identity) registry/tool-specs))
        start-interview (:tool-fn (tools-by-name "start_interview"))
        start-pursuit (:tool-fn (tools-by-name "start_ds_pursuit"))
        formulate-q (:tool-fn (tools-by-name "formulate_question"))
        interpret-r (:tool-fn (tools-by-name "interpret_response"))
        get-ds (:tool-fn (tools-by-name "get_current_ds"))]

    ;; 1. Start interview
    (println "1. Starting interview...")
    (let [{:keys [project_id conversation_id]}
          (start-interview {:project_name "DS Test Brewery"})]
      (println "   Project:" project_id)
      (println "   Conversation:" conversation_id)

      ;; 2. Start warm-up DS
      (println "\n2. Starting warm-up DS...")
      (start-pursuit {:project_id project_id
                      :conversation_id conversation_id
                      :ds_id "process/warm-up-with-challenges"})

      ;; 3. First question
      (println "\n3. Getting first question...")
      (let [{:keys [question context]}
            (formulate-q {:project_id project_id
                          :conversation_id conversation_id
                          :ds_id "process/warm-up-with-challenges"})]
        (println "   Question:" (:text question))
        (println "   Context:" context))

      ;; 4. Submit answer
      (println "\n4. Submitting answer...")
      (let [result (interpret-r {:project_id project_id
                                 :conversation_id conversation_id
                                 :ds_id "process/warm-up-with-challenges"
                                 :answer "We make 5 types of craft beer. Main issues are tank scheduling and seasonal demand."
                                 :question_asked "What products do you make?"})]
        (println "   SCR:" (:scr result))
        (println "   ASCR:" (:updated_ascr result))
        (println "   Complete?" (:ds_complete result)))

      ;; 5. Check database
      (println "\n5. Checking database...")
      (let [conn (connect-atm (keyword project_id))
            scrs (d/q '[:find ?scr ?time
                        :where
                        [?m :message/scr ?scr]
                        [?m :message/timestamp ?time]]
                      @conn)
            ascr-data (d/q '[:find ?data .
                             :in $ ?ds-id
                             :where
                             [?a :ascr/ds-id ?ds-id]
                             [?a :ascr/data ?data]]
                           @conn :process/warm-up-with-challenges)]
        (println "   SCRs in DB:" (count scrs))
        (when (seq scrs)
          (println "   First SCR:" (edn/read-string (ffirst scrs))))
        (println "   ASCR in DB:" (when ascr-data (edn/read-string ascr-data))))

      ;; 6. Continue with more questions...
      (println "\n6. Next question...")
      (let [{:keys [question]}
            (formulate-q {:project_id project_id
                          :conversation_id conversation_id
                          :ds_id "process/warm-up-with-challenges"})]
        (println "   Question:" (:text question)))

      (println "\n=== Test Complete ===")
      {:project_id project_id
       :conversation_id conversation_id})))

(defn check-ascr
  "Check ASCR for a project/DS"
  [project-id ds-id]
  (let [conn (connect-atm (keyword project-id))
        ascr-str (d/q '[:find ?data .
                        :in $ ?ds-id
                        :where
                        [?a :ascr/ds-id ?ds-id]
                        [?a :ascr/data ?data]]
                      @conn (keyword ds-id))]
    (if ascr-str
      (edn/read-string ascr-str)
      {})))

(comment
  ;; Run the test
  (test-ds-flow)

  ;; Check specific ASCR
  (check-ascr "ds-test-brewery" "process/warm-up-with-challenges")

  ;; Query all messages with SCRs
  (let [conn (connect-atm :ds-test-brewery)]
    (d/q '[:find ?q ?a ?scr
           :where
           [?m :message/question ?q]
           [?m :message/content ?a]
           [?m :message/scr ?scr]]
         @conn)))
