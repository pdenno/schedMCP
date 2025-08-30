(ns sched-mcp.dev-helpers
  "Development helper functions for testing and debugging schedMCP"
  (:require
   [clojure.pprint :as pp]
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [connect-atm db-cfg-map]]
   [sched-mcp.util :refer [log!]]
   [sched-mcp.interview :as interview]
   [sched-mcp.tools.iviewr-tools :as tools]
   [sched-mcp.ds-loader :as ds]))

(defn reset-interview-db!
  "Reset a project's database - DESTRUCTIVE!"
  [project-id]
  (let [cfg (db-cfg-map {:type :project :id project-id})]
    (when (d/database-exists? cfg)
      (log! :info (str "Deleting database for" project-id))
      (d/delete-database cfg))
    (log! :info (str "Database reset for" project-id))))

(defn ^:diag quick-test-interview
  "Quick test of interview flow with automated answers"
  [project-name & [domain]]
  (log! :info "\n=== Quick Interview Test ===")
  (log! :info (str "Project:" project-name "\n"))

  ;; Start interview
  (let [start-result (tools/start-interview-tool
                      {:project_name project-name
                       :domain (or domain "manufacturing")})
        _ (pp/pprint start-result)

        project-id (:project_id start-result)
        conv-id (:conversation_id start-result)]

    (when-not (:error start-result)
      ;; Answer first question
      (log! :info "\nAnswering scheduling challenges...")
      (let [answer1 (tools/submit-answer-tool
                     {:project_id project-id
                      :conversation_id conv-id
                      :answer "We have bottlenecks at the packaging station and struggle with equipment changeovers"})]
        (pp/pprint answer1))

      ;; Answer second question
      (log! :info "\nAnswering product/service...")
      (let [answer2 (tools/submit-answer-tool
                     {:project_id project-id
                      :conversation_id conv-id
                      :answer "We manufacture craft beer in various styles"})]
        (pp/pprint answer2))

      ;; Answer third question
      (log! :info "\nAnswering one more thing...")
      (let [answer3 (tools/submit-answer-tool
                     {:project_id project-id
                      :conversation_id conv-id
                      :answer "Seasonal demand varies significantly"})]
        (pp/pprint answer3))

      ;; Get all answers
      (log! :info "\nFinal answers collected:")
      (pp/pprint (tools/get-interview-answers-tool
                  {:project_id project-id
                   :conversation_id conv-id})))))

(defn ^:diag inspect-db
  "Inspect the contents of a project database"
  [project-id]
  (let [conn (connect-atm project-id)]
    (log! :info (str "=== Database Inspection for" project-id "==="))

    ;; Projects
    (log! :info "Projects:")
    (pp/pprint (d/q '[:find ?id ?name
                      :where
                      [?e :project/id ?id]
                      [?e :project/name ?name]]
                    @conn))

    ;; Conversations
    (log! :info "\nConversations:")
    (pp/pprint (d/q '[:find ?cid ?status ?eads
                      :where
                      [?c :conversation/id ?cid]
                      [?c :conversation/status ?status]
                      [?c :conversation/current-eads ?eads]]
                    @conn))

    ;; Messages
    (log! :info "\nMessage count:")
    (log! :info (str (d/q '[:find (count ?m) .
                            :where [?m :message/id]]
                          @conn)))))

(defn ^:diag list-all-ds
  "List all available Discovery Schemas"
  []
  (log! :info "\n=== Available Discovery Schemas ===")
  (let [all-ds (ds/list-available-ds)]
    (doseq [[domain schemas] all-ds]
      (log! :info (str "\n" (name domain) " (" (count schemas) " schemas):"))
      (doseq [schema schemas]
        (log! :info (str "  " (:ds-id schema)))))))

(defn ^:diag show-ds
  "Show details of a specific Discovery Schema"
  [ds-id]
  (if-let [ds (ds/get-ds-by-id ds-id)]
    (do
      (log! :info (str "\n=== Discovery Schema:" ds-id "==="))
      (log! :info (str "\nObjective:" (:interview-objective ds)))
      (log! :info "\nStructure:")
      (pp/pprint (:EADS ds)))
    (log! :info (str "Discovery Schema not found:" ds-id))))

(defn ^:diag current-state
  "Show current state of an interview"
  [project-id]
  (log! :info "\n=== Current Interview State ===")
  (let [context (tools/get-interview-context-tool {:project_id (name project-id)})]
    (if (:error context)
      (log! :info (str "Error:" (:error context)))
      (do
        (log! :info (str "Status:" (:status context)))
        (log! :info (str "Phase:" (:current_phase context)))
        (log! :info (str "Progress:" (:progress context)))
        (when-let [q (:next_question context)]
          (log! :info "\nNext Question:")
          (log! :info (str "ID:" (:id q)))
          (log! :info (str "Text:" (:text q)))
          (log! :info (str "Help:" (:help q))))))))

(defn ^:diag cleanup-test-projects!
  "Remove all test project databases"
  []
  (log! :info "Cleaning up test databases...")
  (doseq [project-id [:test-brewery :test-factory :quick-test]]
    (try
      (reset-interview-db! project-id)
      (catch Exception e
        (log! :info (str "Failed to reset" project-id ":" (.getMessage e)))))))

;; Usage instructions
(defn ^:diag help
  "Show available helper functions"
  []
  (log! :info "
=== schedMCP Development Helpers ===

Testing:
  (quick-test-interview \"Test Brewery\")     - Run automated interview test
  (current-state :test-brewery)             - Show current interview state
  (inspect-db :test-brewery)                - Inspect database contents

Discovery Schemas:
  (list-all-ds)                            - List all available DS
  (show-ds :process/flow-shop)             - Show specific DS details

Cleanup:
  (reset-interview-db! :test-brewery)      - Reset specific project DB
  (cleanup-test-projects!)                 - Remove all test DBs

Remember to require this namespace:
  (require '[sched-mcp.dev-helpers :as dev])
"))

;; Print help on load
(log! :info "Development helpers loaded. Use (help) for available functions.")
