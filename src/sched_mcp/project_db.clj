(ns sched-mcp.project-db
  "Database functions for projects"
  (:require
   [clojure.java.io     :as io]
   [datahike.api        :as d]
   [mount.core          :as mount :refer [defstate]]
   [sched-mcp.schema    :as schema]
   [sched-mcp.sutil     :as sutil :refer [databases-atm connect-atm mocking? resolve-db-id shadow-pid]]
   [sched-mcp.system-db :as sysdb]
   [sched-mcp.util      :as util :refer [log!]]))

(defn project-exists?
  "If a project with argument :project/id (a keyword) exists, return the root entity ID of the project
   (the entity id of the map containing :project/id in the database named by the argumen project-id)."
  ([pid] (project-exists? pid nil))
  ([pid warn?]
   (let [pid (if @mocking? (shadow-pid pid) pid)]
     (assert (keyword? pid))
     (let [res (and (some #(= % pid) (sutil/db-ids))
                    (d/q '[:find ?e .
                           :in $ ?pid
                           :where
                           [?e :project/id ?pid]]
                         @(connect-atm pid) pid))]
       (when (and warn? (not res))
         (log! :warn (str "No project found for pid = " pid)))
       res))))

(defn ^:admin get-project
  "Return the project structure.
   Throw an error if :error is true (default) and project does not exist."
  [pid & {:keys [drop-set error?]
          :or {drop-set #{:db/id} error? true}}]
  (let [conn (connect-atm pid :error? error?)]
    (when-let [eid (project-exists? pid)]
      (resolve-db-id {:db/id eid} conn :drop-set drop-set))))

;;; Conversation update functions for LangGraph integration
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
      (log! :info (str "\n=== Conversations in " project-id " ==="))
      (doseq [[cid status] (sort convs)]
        (log! :info (str cid " - " status)))
      convs)))

(defn update-conversation-state!
  "Update conversation state - designed for LangGraph integration"
  [project-id conversation-id updates]
  (log! :info (str "Updating conversation state: " project-id "/" conversation-id " with " updates))
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [cid (keyword conversation-id)]
      (d/transact conn
                  (vec (for [[k v] updates]
                         [:db/add [:conversation/id cid] k v]))))))

(defn add-conversation-message!
  "Add a message to a conversation"
  [project-id conversation-id message]
  (log! :info (str "Adding message to " project-id "/" conversation-id ": " (select-keys message [:from :content])))
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

(defn ^:diag get-conversation-state
  "Get current conversation state for LangGraph"
  [project-id conversation-id]
  (when-let [conn (connect-atm (keyword project-id) :error? false)]
    (let [db @conn
          cid (keyword conversation-id)]
      (d/pull db
              '[* {:conversation/messages [*]}]
              [:conversation/id cid]))))

;;; EADS (interview phase) tracking
(defn ^:diag get-current-ds
  "Get the current discovery-schema (interview phase) for a conversation"
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

;;; Not yet implemented
(defn update-ascr!
  "Update the aggregate schema-conforming response."
  [_project-id _conversation-id _ds-id]
  nil)

(defn delete-project!
  "Mark a project as deleted (soft delete)"
  [project-id]
  (when-let [conn (connect-atm :system)]
    (let [pid (keyword project-id)]
      (d/transact conn
                  [{:project/id pid
                    :project/status :deleted}])
      {:project-id pid
       :status :deleted})))

(defn list-projects
  "Return a vector of project IDs (excluding deleted projects)"
  []
  (when-let [conn (connect-atm :system)]
    (-> (d/q '[:find [?id ...]
               :where
               [?p :project/id ?id]
               [?p :project/status ?status]
               [(not= ?status :deleted)]]
             @conn)
        sort
        vec)))

(defn unique-project-id
  "Generate a unique project ID. If the requested ID exists, append -1, -2, etc."
  [base-id]
  (let [base-id (keyword base-id)
        existing-ids (set (list-projects))]
    (if (not (contains? existing-ids base-id))
      base-id
      (loop [n 1]
        (let [new-id (keyword (str (name base-id) "-" n))]
          (if (not (contains? existing-ids new-id))
            new-id
            (recur (inc n))))))))

(defn delete-project-db!
  "Delete an existing project database if it exists"
  [project-id]
  (let [pid (keyword project-id)
        cfg (sutil/db-cfg-map {:type :project :id pid})]
    (when (d/database-exists? cfg)
      (log! :info (str "Deleting existing database for: " project-id))
      (d/delete-database cfg))))

(defn create-project!
  "Create a new project in the system database and initialize its database
   Options:
   - :force-replace? - if true, delete existing project with same ID"
  [{:keys [project-id project-name domain conversation-id force-replace?]
    :or {domain "manufacturing"}}]
  (log! :info (str "Creating project: " project-id))
  (let [pid (if force-replace?
              (keyword project-id)
              (unique-project-id project-id))
        cid (keyword (or conversation-id (str (name pid) "-conv-1")))
        timestamp (util/now)]
    ;; Delete existing if force-replace
    (when force-replace?
      (delete-project! pid)
      (delete-project-db! pid))
    ;; Add to system database
    (when-let [sys-conn (connect-atm :system)]
      (d/transact sys-conn
                  [{:project/id pid
                    :project/name project-name
                    :project/domain domain
                    :project/created-at timestamp
                    :project/status :active
                    :project/conversation-id cid}]))
    ;; Create project database
    (let [proj-cfg (sutil/db-cfg-map {:type :project :id pid})]
      ;; Ensure parent directories exist before database operations
      (when-let [db-path (get-in proj-cfg [:store :path])]
        (io/make-parents (str db-path "/dummy")))
      (when-not (d/database-exists? proj-cfg)
        (log! :info (str "Creating project database for: " project-id))
        (d/create-database proj-cfg))
      (sutil/register-db pid proj-cfg)
      ;; Initialize project database with schema
      (when-let [proj-conn (connect-atm pid)]
        (d/transact proj-conn schema/db-schema-proj)
        ;; Add initial project entity and conversation
        (d/transact proj-conn
                    [{:project/id pid
                      :project/name project-name
                      :db/id -1}
                     {:conversation/id cid
                      :conversation/started-at timestamp
                      :conversation/status :active
                      :db/id -2}]))

      {:project-id pid
       :conversation-id cid
       :status :created})))

(defn ^:admin archive-project!
  "Archive a project"
  [project-id]
  (when-let [conn (connect-atm :system)]
    (let [pid (keyword project-id)]
      (d/transact conn
                  [{:project/id pid
                    :project/status :archived}])
      {:project-id pid
       :status :archived})))


;;; -------------------------- Starting and stopping....
(defn register-project-dbs!
  "Register all project databases from the system database"
  []
  (doseq [id (list-projects)]
    (let [proj-cfg (sutil/db-cfg-map {:type :project :id id})]
      (when (d/database-exists? proj-cfg)
        (log! :info (str "Registering project database: " id))
        (sutil/register-db id proj-cfg)))))

(defn init-all-dbs!
  "Initialize system database and register all project databases"
  []
  (log! :info "Initializing all databases...")

  ;; Ensure system database exists
  (sysdb/ensure-system-db!)

  ;; Register all project databases
  (register-project-dbs!)

  {:system-db (sutil/db-cfg-map {:type :system :id :system})
   :project-count (count (list-projects))})

;; Mount defstate for automatic database initialization
(defstate system-and-project-dbs
  :start (init-all-dbs!)
  :stop (log! :info "Shutting down database connections..."))
