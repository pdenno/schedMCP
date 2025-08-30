(ns sched-mcp.system-db
  "System database initialization and management.
   The system database tracks all projects and global configuration."
  (:require
   [datahike.api :as d]
   [sched-mcp.schema :as schema]
   [sched-mcp.sutil :as sutil :refer [db-cfg-map register-db connect-atm]]
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
