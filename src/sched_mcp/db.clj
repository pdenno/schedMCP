(ns sched-mcp.db
  "Database utility functions for inspection, management, and conversation updates"
  (:require
   [clojure.pprint :refer [pprint]]
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [databases-atm connect-atm]]
   [sched-mcp.util :as util :refer [alog!]]))

;;; Database listing and inspection functions

(defn list-all-dbs
  "List all registered databases with their configurations"
  []
  (let [dbs @databases-atm]
    (println "\n=== Registered Databases ===")
    (doseq [[k config] dbs]
      (println (str "\n" k ":"))
      (pprint config))
    (println (str "\nTotal: " (count dbs) " databases"))
    (keys dbs)))

(defn db-info
  "Get detailed information about a specific database"
  [db-id]
  (if-let [config (get @databases-atm db-id)]
    (do
      (println (str "\n=== Database Info: " db-id " ==="))
      (println "\nConfiguration:")
      (pprint config)

      ;; Try to connect and get stats
      (try
        (when-let [conn (connect-atm db-id :error? false)]
          (let [db @conn
                schema (d/schema db)
                stats {:entity-count (count (d/datoms db :eavt))
                       :attribute-count (count schema)
                       :recent-tx (d/q '[:find (max ?tx) .
                                         :where [_ _ _ ?tx]]
                                       db)}]
            (println "\nDatabase Stats:")
            (pprint stats)

            (println "\nSchema attributes:")
            (doseq [[k v] (sort schema)]
              (println (str "  " k " - " (select-keys v [:db/valueType :db/cardinality])))))
          config)
        (catch Exception e
          (println (str "Error connecting to database: " (.getMessage e)))
          config)))
    (println (str "Database not found: " db-id))))

(defn list-entities
  "List all entities in a database, optionally filtered by attribute"
  ([db-id]
   (list-entities db-id nil))
  ([db-id attr-filter]
   (if-let [conn (connect-atm db-id :error? false)]
     (let [db @conn
           q (if attr-filter
               [:find ?e ?v
                :where [?e attr-filter ?v]]
               [:find ?e
                :where [?e :db/ident _]])]
       (d/q q db))
     (println (str "Could not connect to database: " db-id)))))

(defn inspect-entity
  "Pull and display an entity by ID"
  [db-id entity-id]
  (when-let [conn (connect-atm db-id :error? false)]
    (let [db @conn
          entity (d/pull db '[*] entity-id)]
      (println (str "\n=== Entity: " entity-id " ==="))
      (pprint entity)
      entity)))

(defn find-conversations
  "Find all conversations in a project database"
  [project-id]
  (when-let [conn (connect-atm project-id :error? false)]
    (let [db @conn
          convs (d/q '[:find ?cid ?status
                       :where
                       [?c :conversation/id ?cid]
                       [?c :conversation/status ?status]]
                     db)]
      (println (str "\n=== Conversations in " project-id " ==="))
      (doseq [[cid status] (sort convs)]
        (println (str cid " - " status)))
      convs)))

;;; System database functions

(defn list-projects
  "List all projects from the system database"
  []
  (when-let [conn (connect-atm :system :error? false)]
    (let [db @conn
          projects (d/q '[:find ?id ?name ?cid
                          :where
                          [?p :project/id ?id]
                          [?p :project/name ?name]
                          [?p :project/conversation-id ?cid]]
                        db)]
      (println "\n=== Projects in System DB ===")
      (doseq [[id name cid] (sort projects)]
        (println (str id " - \"" name "\" (conversation: " cid ")")))
      projects)))

(defn get-project-info
  "Get detailed project information from system database"
  [project-id]
  (when-let [conn (connect-atm :system :error? false)]
    (let [db @conn
          pid (keyword project-id)
          project (d/q '[:find ?p .
                         :in $ ?pid
                         :where [?p :project/id ?pid]]
                       db pid)]
      (when project
        (d/pull db '[*] project)))))

;;; Conversation update functions for LangGraph integration

(defn update-conversation-state!
  "Update conversation state - designed for LangGraph integration"
  [project-id conversation-id updates]
  (alog! (str "Updating conversation state: " project-id "/" conversation-id " with " updates))
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [cid (keyword conversation-id)]
      (d/transact conn
                  (vec (for [[k v] updates]
                         [:db/add [:conversation/id cid] k v]))))))

(defn add-conversation-message!
  "Add a message to a conversation"
  [project-id conversation-id message]
  (alog! (str "Adding message to " project-id "/" conversation-id ": " (select-keys message [:from :content])))
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [db @conn
          cid (keyword conversation-id)
          max-id (or (d/q '[:find (max ?mid) .
                            :in $ ?cid
                            :where
                            [?c :conversation/id ?cid]
                            [?c :conversation/messages ?m]
                            [?m :message/id ?mid]]
                          db cid)
                     0)
          new-id (inc max-id)
          msg-data (merge {:message/id new-id
                           :message/timestamp (util/now)}
                          message)]
      (d/transact conn
                  [{:conversation/id cid
                    :conversation/messages msg-data}]))))

(defn get-conversation-state
  "Get current conversation state for LangGraph"
  [project-id conversation-id]
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [db @conn
          cid (keyword conversation-id)]
      (d/pull db
              '[* {:conversation/messages [*]}]
              [:conversation/id cid]))))

;;; EADS (interview phase) tracking

(defn get-current-eads
  "Get the current EADS (interview phase) for a conversation"
  [project-id conversation-id]
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [db @conn
          cid (keyword conversation-id)]
      (d/q '[:find ?eads .
             :in $ ?cid
             :where
             [?c :conversation/id ?cid]
             [?c :conversation/current-eads ?eads]]
           db cid))))

(defn update-eads!
  "Update the current EADS for a conversation"
  [project-id conversation-id eads-id]
  (alog! (str "Updating EADS for " project-id "/" conversation-id " to " eads-id))
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (d/transact conn
                [{:conversation/id (keyword conversation-id)
                  :conversation/current-eads (keyword eads-id)}])))

;;; Debug helpers

(defn recent-transactions
  "Show recent transactions in a database"
  [db-id & [limit]]
  (when-let [conn (connect-atm db-id :error? false)]
    (let [db @conn
          limit (or limit 10)
          txs (d/q '[:find ?tx ?inst
                     :where
                     [?tx :db/txInstant ?inst]]
                   db)]
      (println (str "\n=== Recent transactions in " db-id " ==="))
      (doseq [[tx inst] (take limit (reverse (sort-by second txs)))]
        (println (str "tx: " tx " at " inst)))
      txs)))

(defn db-stats
  "Get statistics for all databases"
  []
  (println "\n=== Database Statistics ===")
  (doseq [db-id (keys @databases-atm)]
    (try
      (when-let [conn (connect-atm db-id :error? false)]
        (let [db @conn
              entity-count (count (d/datoms db :eavt))]
          (println (str "\n" db-id ": " entity-count " datoms"))))
      (catch Exception e
        (println (str "\n" db-id ": Error - " (.getMessage e))))))
  :done)
