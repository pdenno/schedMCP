(ns sched-mcp.system-db
  "System database initialization and management.
   The system database tracks all projects and global configuration."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [datahike.api :as d]
   [sched-mcp.schema :as schema]
   [sched-mcp.sutil :as sutil :refer [db-cfg-map register-db connect-atm resolve-db-id root-entities]]
   [sched-mcp.util :as util :refer [log!]]))

(def ^:diag diag (atom nil))

(defn init-system-db!
  "Initialize the system database if it doesn't exist"
  []
  (log! :info "Initializing system database...")
  (let [cfg (db-cfg-map {:type :system :id :system})]
    (reset! diag cfg)
    (try
      ;; Check if database exists
      (if (d/database-exists? cfg)
        (do
          (log! :info "System database already exists, connecting...")
          (register-db :system cfg))
        (do
          (log! :info "Creating new system database...")
          ;; Create the database
          (d/create-database cfg)
          (register-db :system cfg)

          ;; Connect and add schema
          (when-let [conn (connect-atm :system)]
            (log! :info "Adding system schema...")
            (d/transact conn schema/db-schema-sys)

            ;; Add initial system entity
            (log! :info "Adding initial system entity...")
            (d/transact conn
                        [{:system/name "SYSTEM"
                          :db/id -1}])

            (log! :info "System database initialized successfully"))))

      ;; Return connection
      (connect-atm :system)
      (catch Exception e
        (let [msg (str "::system-db-init-error: " (.getMessage e))]
          (log! :error msg)
          (throw (ex-info msg {})))))))

(defn ensure-system-db!
  "Ensure system database exists and is connected"
  []
  (or (connect-atm :system :error? false)
      (init-system-db!)))

;;; ---- Discovery schema management (Discovery Schema are stored in the system DB.)
(defn system-DS?
  "Return a set of DS-ids (keywords) known to the system db. (Often used as a predicate.)"
  []
  (-> (d/q '[:find [?ds-id ...]
             :where [_ :DS/id ?ds-id]]
           @(sutil/connect-atm :system))
      set))

(defn put-DS-instructions!
  "Update the system DB with a (presumably) new version of the argument DS instructions.
   Of course, this is a development-time activity."
  [{:keys [DS budget-decrement] :as ds-instructions}]
  (let [id (:DS-id DS)
        db-obj {:DS/id id
                :DS/budget-decrement (or budget-decrement 0.05)
                :DS/msg-str (str ds-instructions)}
        conn (connect-atm :system)
        eid (d/q '[:find ?e . :where [?e :system/name "SYSTEM"]] @conn)]
    (log! :info (str "Writing DS instructions to system DB: " id))
    (d/transact conn {:tx-data [{:db/id eid :system/DS db-obj}]}))
  nil)

(defn get-DS-instructions
  "Return the full DS instructions object maintained in the system DB
   (the EDN structure from edn/read-string of :DS/msg-str).
   Returns the empty string when the DS ID is not known."
  [ds-id]
  (assert (keyword? ds-id))
  (if-let [msg-str (d/q '[:find ?msg-str .
                          :in $ ?ds-id
                          :where
                          [?e :DS/id ?ds-id]
                          [?e :DS/msg-str ?msg-str]]
                        @(connect-atm :system) ds-id)]
    (edn/read-string msg-str)
    ""))

(defn same-DS-instructions?
  "Return true if the argument ds-instructions (an EDN object) is exactly what the system already maintains."
  [ds-instructions]
  (let [id (-> ds-instructions :DS :DS-id)]
    (= ds-instructions (get-DS-instructions id))))

(defn ^:admin update-all-DS-json!
  "Copy JSON versions of the system DB's DS instructions to the files in resources/agents/iviewrs/DS."
  []
  (doseq [ds-id (system-DS?)]
    (if-let [ds-instructions (-> ds-id get-DS-instructions not-empty)]
      (sutil/update-resources-DS-json! ds-instructions)
      (log! :error (str "No such DS instructions " ds-id)))))

(defn ^:admin get-system
  "Return the project structure.
   Throw an error if :error is true (default) and project does not exist."
  []
  (let [conn-atm (connect-atm :system)]
    (when-let [eid (d/q '[:find ?eid . :where [?eid :system/name "SYSTEM"]] @conn-atm)]
      (resolve-db-id {:db/id eid} conn-atm))))

(defn ^:admin backup-system-db
  "Backup the system database to an EDN file"
  [& {:keys [target-dir] :or {target-dir "data/"}}]
  (io/make-parents (str target-dir "dummy"))
  (let [conn-atm (connect-atm :system)
        filename (str target-dir "system-db.edn")
        s (with-out-str
            (println "[")
            (doseq [ent-id (root-entities conn-atm)]
              (let [obj (resolve-db-id {:db/id ent-id} conn-atm)]
                ;; Skip schema elements and transaction markers
                (when-not (and (map? obj)
                               (or (contains? obj :db/ident)
                                   (contains? obj :db/txInstant)))
                  (pprint obj)
                  (println))))
            (println "]"))]
    (log! :info (str "Writing system DB to " filename))
    (spit filename s)))

(defn recreate-system-db!
  "Recreate the system database from an EDN file."
  [& {:keys [target-dir]
      :or {target-dir "data/"}}]
  (if (.exists (io/file (str target-dir "system-db.edn")))
    (let [cfg (db-cfg-map {:type :system})]
      (log! :info "Recreating the system database.")
      (when (d/database-exists? cfg) (d/delete-database cfg))
      (d/create-database cfg)
      (register-db :system cfg)
      (let [conn (connect-atm :system)]
        (d/transact conn schema/db-schema-sys)
        (d/transact conn (-> "data/system-db.edn" slurp edn/read-string))
        cfg))
    (log! :error "Not recreating system DB: No backup file.")))

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

(defn add-project-to-system
  "Add the argument project (a db-cfg map) to the system database."
  [id project-name dir]
  (let [conn-atm (connect-atm :system)
        eid (d/q '[:find ?eid . :where [?eid :system/name "SYSTEM"]] @conn-atm)]
    (d/transact conn-atm {:tx-data [{:db/id eid
                                     :system/projects {:project/id id
                                                       :project/name project-name
                                                       :project/dir dir
                                                       :project/status :active}}]})))
(defn ^:admin archive-project!
  "Archive a project"
  [pid]
  (when-let [conn (connect-atm :system)]
    (d/transact conn
                [{:project/id pid
                  :project/status :archived}])
    {:pid pid
     :status :archived}))

(defn mark-project-deleted-in-sdb!
  "Mark a project as deleted (soft delete)"
  [pid]
  (when-let [conn (connect-atm :system)]
    (let [pid (keyword pid)]
      (d/transact conn
                  [{:project/id pid
                    :project/status :deleted}])
      {:pid pid
       :status :deleted})))

(defn get-discovery-schema
  "Return the discovery-schema identified by the argument ds-id."
  [ds-id]
  (assert (keyword? ds-id))
  (if-let [s (d/q '[:find ?str .
                    :in $ ?ds-id
                    :where
                    [?e :DS/id ?ds-id]
                    [?e :DS/msg-str ?str]]
                  @(connect-atm :system) ds-id)]
    (-> s edn/read-string (dissoc :message-type))
    (throw (ex-info "No such discovery-schema" {:ds-id ds-id}))))


(defn get-discovery-schema-JSON
  "Return the JSON of the discovery schema from the system DB."
  [ds-id]
  (-> ds-id get-discovery-schema sutil/clj2json-pretty))


(defn ^:admin delete-project!
  "Remove project from the system."
  [pid]
  (if (some #(= % pid) (list-projects))
    (let [conn-atm (connect-atm :system)]
      ;; Remove from system DB.
      (when-let [s-eid (d/q '[:find ?e . :in $ ?pid :where [?e :project/id ?pid]] @conn-atm pid)]
        (let [obj (resolve-db-id {:db/id s-eid} conn-atm)]
          (d/transact (connect-atm :system) {:tx-data (for [[k v] obj] [:db/retract s-eid k v])})))
      ;; Remove DB files
      (let [cfg (db-cfg-map {:type :project :id pid})]
        (d/delete-database cfg)
        (sutil/deregister-db pid)
        (when-let [base-dir (:base-dir cfg)]
          (let [dir (str base-dir "/projects/" (name pid) "/")]
            (when (.exists (io/file dir))
              (sutil/delete-directory-recursive dir))))
        nil))
    (log! :warn (str "Delete-project: Project not found: " pid))))
