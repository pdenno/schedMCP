(ns sched-mcp.project-db
  "Database functions for projects - includes backup/restore for schema migrations"
  (:require
   [clojure.edn :as edn]
   [clojure.core.unify :as u]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [datahike.api :as d]
   [datahike.pull-api :as dp]
   [mount.core :as mount :refer [defstate]]
   [sched-mcp.schema :as schema :refer [project-schema-key?]]
   [sched-mcp.sutil :as sutil :refer [connect-atm mocking? resolve-db-id shadow-pid]]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :as util :refer [log! now]]))

(def ^:diag diag (atom nil))

;;; Throughout this file, variables named pid are project IDs (a keyword) and variables named cid are conversations IDs #{:process, :data, :resources, :optimality}.

(defn project-exists?
  "If a project with argument :project/id (a keyword) exists, return the root entity ID of the project
   (the entity id of the map containing :project/id in the database named by the argumen pid)."
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

;;; Not yet implemented
(defn update-ascr!
  "Update the aggregate schema-conforming response."
  [_pid _cid _ds-id]
  nil)
;;; --------------------------------------- Messages ---------------------------------------------------------------------
(declare get-conversation)

(defn message-exists?
  "Return the :db/id if a message with the argument coordinates is found."
  [pid cid mid]
  (d/q '[:find ?eid .
         :in $ ?cid ?mid
         :where
         [?c :conversation/id ?cid]
         [?c :conversation/messages ?eid]
         [?eid :message/id ?mid]]
       @(connect-atm pid) cid mid))

(defn max-msg-id
  "Return the current highest message ID used in the project."
  [pid]
  (let [ids (d/q '[:find [?msg-id] :where [_ :message/id ?msg-id]] @(connect-atm pid))]
    (if (empty? ids) 0 (apply max ids))))

(declare get-active-DS-id conversation-exists?)

;;; See "Map forms" at https://docs.datomic.com/pro/transactions/transactions.html
;;; This is typical: You get the eid of the thing you want to add properties to.
;;; You specify that as the :db/id and then just add whatever you want for the properties.
;;; If the property is cardinality many, it will add values, not overwrite them.
(defn add-msg!
  "Create a message object and add it to current conversation of the database with :project/id = id.
   Return the :message/id."
  [{:keys [pid cid from content table code tags pursuing-DS answers-question]}]
  (assert (keyword? cid))
  (assert (#{:system :human :surrogate :developer-injected} from))
  (assert (string? content))
  (assert (not= content "null"))
  (if-let [conn (connect-atm pid)]
    (let [msg-id (inc (max-msg-id pid))]
      (d/transact conn {:tx-data [{:db/id (conversation-exists? pid cid)
                                   :conversation/messages (cond-> #:message{:id msg-id :from from :timestamp (now) :content content}
                                                            table             (assoc :message/table (str table))
                                                            (not-empty tags)  (assoc :message/tags tags)
                                                            pursuing-DS       (assoc :message/pursuing-DS pursuing-DS)
                                                            code              (assoc :message/code code)
                                                            answers-question  (assoc :message/answers-question))}]})
      msg-id)
    (throw (ex-info "Could not connect to DB." {:pid pid}))))

(defn get-msg
  "Return the complete message specified by the arguments."
  [pid cid mid]
  (let [conn @(connect-atm pid)
        eid (d/q '[:find ?m-ent .
                   :in $ ?cid ?mid
                   :where
                   [?c-ent :conversation/id ?cid]
                   [?c-ent :conversation/messages ?m-ent]
                   [?m-ent :message/id ?mid]]
                 conn cid mid)]
    (dp/pull conn '[*] eid)))

(defn most-recent-unanswered
  "Find the most recent unanswered question in a conversation.
   Returns the message ID or nil if no unanswered questions."
  [pid cid]
  (when-let [conn (connect-atm pid)]
    (let [unanswered-q (d/q '[:find ?mid ?time
                              :in $ ?cid
                              :where
                              [?m :message/id ?mid]
                              [?m :message/from :system]
                              [?m :message/timestamp ?time]
                              [?c :conversation/id ?cid]
                              [?c :conversation/messages ?m]
                             ;; Not answered yet - no message has this as answers-question
                              (not-join [?mid] [_ :message/answers-question ?mid])]
                            @conn cid)]
      ;; Get the most recent unanswered question
      (when (seq unanswered-q)
        (-> (sort-by second > unanswered-q)
            first
            first)))))

(defn update-msg!
  "Update the message with given info (a merge)."
  [pid cid mid {:message/keys [answers-question graph--orm] :as info}]
  (if-let [eid (message-exists? pid cid mid)]
    (do
      ;; Special validation for answers-question
      (when answers-question
        (if (= mid answers-question)
          (throw (ex-info "Attempting to mark a message as answer the question it raises."
                          {:pid pid :cid cid :mid mid}))))
      ;; Transact all the info attributes
      (d/transact (connect-atm pid) {:tx-data [(merge {:db/id eid} info)]}))
    (log! :warn (str "Could not find msg for update-msg: pid = " pid " cid = " cid " mid = " mid))))

;;; --------------------------------------- Conversations ---------------------------------------------------------------------
(defn conversation-exists?
  "Return the eid of the conversation if it exists."
  [pid cid]
  (assert (#{:process :data :resources :optimality} cid))
  (d/q '[:find ?eid .
         :in $ ?cid
         :where [?eid :conversation/id ?cid]]
       @(connect-atm pid) cid))

(defn get-conversation
  "For the argument project (pid) return a map of a DB conversation object with the :conversation/messages sorted by :message/id."
  [pid cid]
  (assert (#{:process :data :resources :optimality} cid))
  (if-let [eid (conversation-exists? pid cid)]
    (-> (resolve-db-id {:db/id eid} (connect-atm pid))
        (update :conversation/messages #(->> % (sort-by :message/id) vec)))
    {}))

(defn put-conversation-status!
  "Set the project' s:converation/status attribute to one of #{:ds-exhausted :not-started :in-progress}."
  [pid cid status]
  (assert (#{:ds-exhausted :not-started :in-progress} status))
  (if-let [eid (conversation-exists? pid cid)]
    (d/transact (connect-atm pid) {:tx-data [[:db/add eid :conversation/status status]]})
    (log! :error (str "No such conversation: pid = " pid " cid = " cid))))

(defn get-active-cid
  "Get what the DB asserts is the project's current conversation CID, or :process if it doesn't have a value."
  [pid]
  (let [pid (if @mocking? (shadow-pid pid) pid)]
    (or (d/q '[:find ?cid . :where [_ :project/active-conversation ?cid]] @(connect-atm pid))
        :process)))

(defn put-active-cid!
  [pid cid]
  (let [pid (if @mocking? (shadow-pid pid) pid)]
    (assert (#{:process :data :resources :optimality} cid))
    (if-let [eid (project-exists? pid)]
      (d/transact (connect-atm pid) {:tx-data [{:db/id eid
                                                :project/active-conversation cid}]})
      (log! :error "Could not put-active-cid!"))))

;;;--------------------------------------- project itself --------------------------------------
(defn unique-pid
  "Generate a unique project ID. If the requested ID exists, append -1, -2, etc."
  [base-id]
  (let [base-id (keyword base-id)
        existing-ids (set (sdb/list-projects))]
    (if (not (contains? existing-ids base-id))
      base-id
      (loop [n 1]
        (let [new-id (keyword (str (name base-id) "-" n))]
          (if (not (contains? existing-ids new-id))
            new-id
            (recur (inc n))))))))

(defn delete-project-db!
  "Delete an existing project database if it exists"
  [pid]
  (let [pid (keyword pid)
        cfg (sutil/db-cfg-map {:type :project :id pid})]
    (sdb/mark-project-deleted-in-sdb! pid)
    (when (d/database-exists? cfg)
      (log! :info (str "Deleting existing database for: " pid))
      (d/delete-database cfg))))

;;; ToDo: Write clojure spec for whole projects!
(def conversation-defaults
  [{:conversation/id :process
    :conversation/active-DS-id :process/warm-up-with-challenges ; We assume things start here.
    :conversation/status :in-progress} ; We assume things start here.
   {:conversation/id :data
    :conversation/status :not-started}
   {:conversation/id :resources
    :conversation/status :not-started}
   {:conversation/id :optimality
    :conversation/status :not-started}])

(defn create-project-db!
  "Create a new project in the system database and initialize its database to start with DS = :process/warm-up-with-challenges.
   Options:
   - :force-replace? - if true, delete existing project with same ID
   - :additional-info is a map with project-level info to be merged (taking precedence) with the original content created here,
     for example {:project/surrogate {:surrogate/system-instruction 'blah, blah...'}}.
   Returns a unique PID (might not be same as the argument)."
  [{:keys [pid project-name _domain cid force-replace? in-mem? additional-info]
    :or {project-name "Unnamed project"
         additional-inf {}
         cid :process}}]
  (log! :info (str "Creating project: " pid))
  (assert (#{:process :data :resources :optimality} cid))
  (let [pid (if force-replace? pid (unique-pid pid))
        cfg (sutil/db-cfg-map {:type :project :id pid :in-mem? in-mem?})
        dir (-> cfg :store :path)
        pname (as-> project-name ?s (str/split ?s #"\s+") (interpose "-" ?s) (apply str ?s))
        files-dir (-> cfg :base-dir (str "/projects/" pname "/files"))]
    (when-not in-mem?
      (when-not (-> dir io/as-file .isDirectory)
        (-> cfg :store :path io/make-parents)
        (-> cfg :store :path io/as-file .mkdir))
      (when-not (-> files-dir io/as-file .isDirectory)
        (io/make-parents files-dir)
        (-> files-dir io/as-file .mkdir))
      (sdb/add-project-to-system pid project-name dir))
    (when (d/database-exists? cfg) (d/delete-database cfg))
    (d/create-database cfg)
    (sutil/register-db pid cfg)
     ;; Add to project db
    (d/transact (connect-atm pid) schema/db-schema-proj)
    (d/transact (connect-atm pid) {:tx-data (cond-> {:project/id pid
                                                     :project/name project-name
                                                     ;; REMOVED :project/status - it belongs in system DB
                                                     :project/execution-status :running
                                                     :project/active-conversation :process
                                                     :project/claims [{:claim/string (str `(~'project-id ~pid))}
                                                                      {:claim/string (str `(~'project-name ~pid ~project-name))}]
                                                     :project/conversations conversation-defaults}
                                              (not-empty additional-info)  (merge additional-info)
                                              in-mem?                      (into {:project/in-memory? true})
                                              true                         vector)})
    (log! :info (str "Created project database for " pid))
    {:pid (reset! diag pid)
     :cid cid
     :status :created}))

;;; ------------------- Discovery Schema (DS) and Schema-conforming Response (SCR) -----------------------------------------
;;; DS (interview phase) tracking
(defn ^:diag get-current-DS
  "Get the current discovery-schema (interview phase) for a conversation"
  [pid cid]
  (when-let [conn (connect-atm (keyword pid) :error? false)]
    (let [db @conn
          cid (keyword cid)]
      (d/q '[:find ?ds .
             :in $ ?cid
             :where
             [?c :conversation/id ?cid]
             [?c :conversation/current-ds ?ds]]
           db cid))))

(defn get-msg-SCR
  "Return a vector of DS data structure maps matching for the given project id and DS-id.
   The vector returned is sorted by msg-id, so it is chronological (most recent last).
   Included in the maps are :msg-id where it was found and DS-ref that it is about,
   which is the same as the argument ds-id."
  [pid ds-id]
  (let [db-res (d/q '[:find ?str ?msg-id
                      :keys s msg-id
                      :in $ ?ds-id
                      :where
                      [?e :message/pursuing-DS ?ds-id]
                      [?e :message/SCR ?str]
                      [?e :message/id ?msg-id]]
                    @(connect-atm pid) ds-id)
        dstructs (reduce (fn [r {:keys [s msg-id]}]
                           (let [data-structure (edn/read-string s)]
                             (conj r (-> data-structure
                                         (assoc :msg-id msg-id)
                                         (assoc :DS-ref ds-id)))))
                         []
                         db-res)]
    (->> dstructs (sort-by :msg-id) vec)))

(defn put-msg-SCR!
  "Attach a stringified representation of the SCR is building to the latest message.
   The data structure should have keywords for keys at this point (not checked)."
  [pid cid ds]
  (let [max-id (max-msg-id pid)
        conn-atm (connect-atm pid)
        eid (d/q '[:find ?eid .
                   :in $ ?cid ?max-id
                   :where
                   [?conv :conversation/id ?cid]
                   [?conv :conversation/messages ?eid]
                   [?eid :message/id ?max-id]] @conn-atm cid max-id)]
    (if eid
      (d/transact conn-atm {:tx-data [{:db/id eid :message/SCR (str ds)}]})
      (log! :error (str "No such conversation: " cid)))))

(defn get-active-DS-id
  "Return the DS-id (keyword) for whatever DS is active in the given project and conversation.
   See also get-project-active-DS-id."
  [pid cid]
  (d/q '[:find ?ds-id .
         :in $ ?cid
         :where
         [?e :conversation/id ?cid]
         [?e :conversation/active-DS-id ?ds-id]]
       @(connect-atm pid) cid))

(defn put-active-DS-id
  "Set :conversation/active-DS. See also put-project-active-DS-id"
  [pid cid ds-id]
  (let [eid (conversation-exists? pid cid)]
    (d/transact (connect-atm pid)
                {:tx-data [{:db/id eid :conversation/active-DS-id ds-id}]})))

(defn get-interview-progress
  "Get overall interview progress across all DS"
  [pid]
  (let [conn (connect-atm pid)]
    (if-not conn
      {:error "Project not found"}
      (let [;; Get all ASCRs in the project
            ascrs (d/q '[:find ?ds-id ?completed ?budget-left
                         :where
                         [?e :ascr/id ?ds-id]
                         [?e :ascr/completed? ?completed]
                         [?e :ascr/budget-left ?budget-left]]
                       @conn)
            ;; Get current active DS and conversation
            active-cid (get-active-cid pid)
            active-ds-id (when active-cid (get-active-DS-id pid active-cid))
            ;; Calculate summary stats
            total-ds (count ascrs)
            completed-ds (count (filter #(second %) ascrs))
            in-progress-ds (count (filter #(and (not (second %)) (> (nth % 2) 0)) ascrs))]
        {:total_ds total-ds
         :completed_ds completed-ds
         :in_progress_ds in-progress-ds
         :completion_percentage (if (zero? total-ds)
                                  0
                                  (int (* 100 (/ completed-ds total-ds))))
         :active_conversation (when active-cid (name active-cid))
         :current_ds (when active-ds-id (name active-ds-id))
         :ds_details (map (fn [[ds-id completed budget]]
                            {:ds_id (name ds-id)
                             :completed completed
                             :budget_left budget})
                          ascrs)}))))

;;; -------------------- Aggregate Schema Conforming Response (ASCR)  -----------------------
(defn ASCR-exists?
  [pid ds-id]
  (d/q '[:find ?eid .
         :in $ ?ds-id
         :where [?eid :ascr/id ?ds-id]]
       @(connect-atm pid) ds-id))

(defn mark-ASCR-complete!
  [pid ds-id]
  (if-let [eid (ASCR-exists? pid ds-id)]
    (d/transact (connect-atm pid) {:tx-data [{:db/id eid :ascr/completed? true}]})
    (log! :error (str "ASCR does not exist: ds-id = " ds-id " pid = " pid))))

(defn init-ASCR!
  "Add a summary data-structure with the given ds-id an budget = 1.0 to the argument project."
  [pid ds-id]
  (if-let [eid (project-exists? pid)]
    (d/transact (connect-atm pid) {:tx-data [{:db/id eid
                                              :project/ASCRs
                                              {:ascr/id ds-id
                                               :ascr/budget-left 1.0}}]})
    (log! :error (str "Project not found " pid))))

(defn get-questioning-budget-left!
  "The project contains dstruct objects for everything that has been started.
   If one can't be found (it hasn't started) this returns 1.0, otherwise it
   returns the value of :ascr/budget-left for the given ds-id.
   This will create the summary data structure if it doesn't exist."
  [pid ds-id]
  (assert (or (nil? ds-id) ((sdb/system-DS?) ds-id)))
  (if-let [eid (ASCR-exists? pid ds-id)]
    (d/q '[:find ?left . :in $ ?eid :where [?eid :ascr/budget-left ?left]] @(connect-atm pid) eid)
    (do (when ds-id (init-ASCR! pid ds-id))
        1.0)))

(def default-DS-budget-decrement 0.05)

(defn reduce-questioning-budget!
  "The system stores DS-instructions that may or may not have an :DS/budget-decrement.
   If it does, decrement the same-named summary structure objec by that value.
   Otherwise, decrement the same-named object by the db/default-DS-budget-decrement."
  [pid ds-id]
  (assert ((sdb/system-DS?) ds-id))
  (let [val (get-questioning-budget-left! pid ds-id)
        dec-val (or (d/q '[:find ?dec-val .
                           :in $ ?ds-id
                           :where
                           [?e :DS/id ?ds-id]
                           [?e :DS/budget-decrement ?dec-val]]
                         @(connect-atm :system) ds-id)
                    default-DS-budget-decrement)
        eid (d/q '[:find ?eid .
                   :in $ ?ds-id
                   :where
                   [?eid :ascr/id ?ds-id]]
                 @(connect-atm pid) ds-id)]
    (if eid
      (d/transact (connect-atm pid) {:tx-data [{:db/id eid :ascr/budget-left (- val dec-val)}]})
      (log! :error (str "No summary structure for DS id " ds-id)))))

(defn get-ASCR
  "Return the ASCR given pid and ds-id. If no such data structure yet, returns {}."
  [pid ds-id]
  (if-let [eid (ASCR-exists? pid ds-id)]
    (as-> (resolve-db-id {:db/id eid} (connect-atm pid)) ?a
      (assoc ?a :ascr/dstruct (-> ?a :ascr/str edn/read-string))
      (dissoc ?a :ascr/str))
    (do (log! :warn (str "No ASCR for " ds-id)) {})))

(defn ^:diag list-ASCR
  "Return a vector of ds-id for summary-ds of the given project."
  [pid]
  (d/q '[:find [?ds-id ...]
         :where
         [_ :ascr/id ?ds-id]]
       @(connect-atm pid)))

(defn put-ASCR!
  "Dehydrate the given summary data structure and write it to the project DB."
  [pid ds-id dstruct]
  (let [dstruct (dissoc dstruct :msg-id)
        eid (d/q '[:find ?eid . :where [?eid :project/id]] @(connect-atm pid))]
    (if eid
      (d/transact (connect-atm pid)
                  {:tx-data [{:db/id eid
                              :project/ASCRs {:ascr/id ds-id
                                              :ascr/str (str dstruct)}}]})
      (throw (ex-info "No eid" {:pid pid :eid eid})))))

(defn key-xy
  [obj]
  (cond (map? obj) (reduce-kv (fn [m k v] (if (#{"x" "y"} k)
                                            (assoc m (keyword k) v)
                                            (assoc m k (key-xy v))))
                              {}
                              obj)
        (vector? obj) (mapv key-xy obj)
        :else obj))

(defn set-orm-layout!
  "Called when a user changes the layout of an ORM diagram on the UI, this makes that data persistent
   in the stringified DS object. Note that four coordinates are needed to place it correctly!"
  [{:keys [pid cid message-id inquiry-area-id layout-data] :as msg}]
  (log! :info (str "save-orm-layout!:\n " (with-out-str (pprint msg))))
  (if-let [orm-ds (-> (get-msg pid cid message-id) :message/graph--orm edn/read-string)]
    (let [orm-ds (update orm-ds :inquiry-areas (fn [ias] (mapv #(if (= (:inquiry-area-id %) inquiry-area-id)
                                                                  (assoc % :layout (key-xy layout-data))
                                                                  %)
                                                               ias)))]
      (update-msg! pid cid message-id {:message/graph--orm (str orm-ds)}))
    (log! :error (str "Could not find ORM diagram for " msg))))

(defn get-project-active-DS-id
  [pid]
  (let [pid (if @mocking? (shadow-pid pid) pid)]
    (d/q '[:find ?ds-id .
           :where
           [_ :project/active-DS-id ?ds-id]]
         @(connect-atm pid))))

(defn put-project-active-DS-id!
  [pid ds-id]
  (let [pid (if @mocking? (shadow-pid pid) pid)]
    (assert ((sdb/system-DS?) ds-id))
    (if-let [eid (project-exists? pid)]
      (d/transact (connect-atm pid) {:tx-data [{:db/id eid
                                                :project/active-DS-id ds-id}]})
      (log! :error "Project does not exist."))))

;;; ------------------------ Project Database Utilities (schema migration, etc ) --------------------------
(defn clean-project-for-schema
  "Remove attributes that are no longer in the schema and nil values"
  [proj]
  (letfn [(clean [x]
            (cond
              (map? x)
              (reduce-kv (fn [m k v]
                           (cond
                             (nil? v) m ; Drop nil values
                             (not (project-schema-key? k))
                             (do (log! :warn (str "Dropping obsolete attr: " k)) m)
                             :else (assoc m k (clean v))))
                         {} x)

              (vector? x)
              (->> (mapv clean x) (remove nil?) vec)

              :else x))]
    (clean proj)))

(defn backup-project-db
  "Backup a project database to EDN file"
  [pid & {:keys [target-dir clean?]
          :or {target-dir "data/projects/" clean? true}}]
  (io/make-parents (str target-dir "dummy")) ; Ensure dir exists
  (let [filename (str target-dir (name pid) ".edn")
        proj (cond-> (get-project pid)
               clean? clean-project-for-schema)
        s (with-out-str
            (println "[")
            (pprint proj)
            (println "]"))]
    (log! :info (str "Writing project to " filename))
    (spit filename s)))

(defn backup-all-projects
  "Backup all project databases"
  [& {:keys [target-dir] :or {target-dir "data/projects/"}}]
  (doseq [pid (sdb/list-projects)]
    (backup-project-db pid :target-dir target-dir)))

(defn recreate-project-db!
  "Recreate a project database from EDN backup
   Can provide content directly or read from backup file"
  ([pid] (recreate-project-db! pid nil))
  ([pid content]
   (let [backup-file (format "data/projects/%s.edn" (name pid))]
     (if (or content (.exists (io/file backup-file)))
       (let [cfg (sutil/db-cfg-map {:type :project :id pid})
             pname (or (when content (:project/name content))
                       ;; Generate name from pid if not in content
                       (as-> (name pid) ?s
                         (str/replace ?s #"-" " ")
                         (str/split ?s #"\s+")
                         (map str/capitalize ?s)
                         (str/join " " ?s)))]

         ;; Delete existing DB if present
         (when (d/database-exists? cfg)
           (d/delete-database cfg))

         ;; Create new database
         (d/create-database cfg)
         (sutil/register-db pid cfg)

         ;; Add to system DB if not already there
         (when-let [sys-conn (connect-atm :system :error? false)]
           (when-not (d/q '[:find ?e . :in $ ?pid :where [?e :project/id ?pid]]
                          @sys-conn pid)
             (d/transact sys-conn
                         [{:project/id pid
                           :project/name pname
                           :project/created-at (java.util.Date.)
                           :project/status :active}])))

         ;; Load content
         (let [content (if content
                         (-> content
                             (assoc :project/id pid)
                             (assoc :project/name pname))
                         (->> backup-file slurp edn/read-string first))]

           ;; Transact schema and content
           (d/transact (connect-atm pid) schema/db-schema-proj)
           (d/transact (connect-atm pid) [content]))

         (log! :info (str "Recreated project DB: " pid))
         cfg)
       (log! :error (str "Not recreating DB - backup file missing: " backup-file))))))

(defn update-project-for-schema!
  "Backup and recreate a project to update its schema"
  [pid]
  (backup-project-db pid)
  (recreate-project-db! pid))

(defn ^:admin update-all-projects-for-schema!
  "Update all projects for new schema"
  []
  (log! :info "Updating all projects for new schema...")
  (doseq [pid (sdb/list-projects)]
    (try
      (update-project-for-schema! pid)
      (catch Exception e
        (log! :error (str "Failed to update " pid ": " (.getMessage e)))))))

(def keep-db? #{:him})
(defn ^:admin recreate-dbs!
  "Recreate the system DB on storage from backup.
   For each project it lists, recreate it from backup if such backup exists."
  []
  (swap! sutil/databases-atm
         #(reduce-kv (fn [res k v] (if (keep-db? k) (assoc res k v) res)) {} %))
  (sdb/recreate-system-db!)
  (log! :info (str "Recreating these projects: " (sdb/list-projects)))
  (doseq [pid (sdb/list-projects)]
    (recreate-project-db! pid)))

;; ===== Claims functionality =====
(defn get-claims
  "Return the claims (ground propositions) for the argument project.
   Options:
     :objects? - if true, return full claim objects with metadata,
                 otherwise return just the predicate forms as a set."
  [pid & {:keys [objects?]}]
  (let [conn @(connect-atm pid)]
    (if objects?
      ;; Return full claim objects with metadata
      (let [eids (d/q '[:find [?eid ...] :where [?eid :claim/string]] conn)]
        (for [eid eids]
          (let [{:claim/keys [string conversation-id confidence]}
                (dp/pull conn '[*] eid)]
            (cond-> {:claim (edn/read-string string)}
              conversation-id (assoc :conversation-id conversation-id)
              confidence (assoc :confidence confidence)))))
      ;; Return just predicate forms as a set
      (if-let [facts (d/q '[:find [?s ...]
                            :where
                            [_ :project/claims ?pp]
                            [?pp :claim/string ?s]]
                          conn)]
        (-> (mapv edn/read-string facts) set)
        #{}))))

(defn add-claim!
  "Add a claim (ground proposition) to the project.
   claim - a predicate form (list) e.g. '(surrogate :sur-craft-beer)
   opts - optional map with :conversation-id and/or :confidence

   Example usage:
     (add-claim! :sur-craft-beer '(surrogate :sur-craft-beer))
     (add-claim! :my-project '(expert-type :my-project :surrogate) {:conversation-id :process})
     (add-claim! :my-project '(has-resource :my-project \"drill-press\") {:confidence 1})

   Returns the transaction result."
  ([pid claim] (add-claim! pid claim {}))
  ([pid claim {:keys [conversation-id confidence] :as opts}]
   (assert (list? claim) "Claim must be a list")
   (let [conn (connect-atm pid)
         proj-eid (d/q '[:find ?eid . :where [?eid :project/id]] @conn)]
     (if proj-eid
       (d/transact conn {:tx-data [{:db/id proj-eid
                                    :project/claims
                                    (cond-> {:claim/string (str claim)}
                                      conversation-id (assoc :claim/conversation-id conversation-id)
                                      confidence (assoc :claim/confidence confidence))}]})
       (throw (ex-info "No project entity found" {:pid pid}))))))

(defn claim-exists?
  "Check if a specific claim exists in the project.
   predicate can be either a string or a Clojure form.

   Example:
     (claim-exists? :sur-craft-beer '(surrogate :sur-craft-beer))
     (claim-exists? :sur-craft-beer \"(surrogate :sur-craft-beer)\")"
  [pid predicate]
  (let [claim-str (if (string? predicate) predicate (str predicate))
        claims (get-claims pid)]
    (contains? claims (edn/read-string claim-str))))

(defn surrogate-project?
  "Returns true if the project is using a surrogate expert.
   Checks for claims like (surrogate <pid>) or (expert-type <pid> :surrogate)."
  [pid]
  (let [claims (get-claims pid)]
    (or (contains? claims (list 'surrogate pid))
        (contains? claims (list 'expert-type pid :surrogate)))))

(defn unify-claim
  "Unify a claim pattern against all claims in the project.
   Returns a vector of non-empty binding maps, or nil if no matches.

   pid - the project ID
   pattern - a claim form with logic variables, e.g. '(project-name ?pid ?name)

   Example:
     (unify-claim :sur-craft-beer '(project-name ?pid ?name))
     => [{?pid :sur-craft-beer, ?name \"Craft Beer Interview\"}]

     (unify-claim :sur-craft-beer '(surrogate ?p))
     => [{?p :sur-craft-beer}]

     (unify-claim :sur-craft-beer '(expert-type ?p :surrogate))
     => nil  ; if no such claim exists"
  [pid pattern]
  (let [claims (get-claims pid)
        results (for [claim claims]
                  (try
                    (u/unify pattern claim)
                    (catch Exception _ nil)))]
    (when-let [bindings (seq (remove nil? results))]
      (vec bindings))))

(defn find-claims
  "Find all claims matching a pattern, returning both the claim and bindings.
   Returns a vector of maps with :claim and :bindings keys.

   Example:
     (find-claims :sur-craft-beer '(project-name ?pid ?name))
     => [{:claim '(project-name :sur-craft-beer \"Test\")
          :bindings {?pid :sur-craft-beer, ?name \"Test\"}}]"
  [pid pattern]
  (let [claims (get-claims pid)]
    (vec (for [claim claims
               :let [bindings (try (u/unify pattern claim)
                                   (catch Exception _ nil))]
               :when bindings]
           {:claim claim
            :bindings bindings}))))

(defn query-claims
  "Query claims using multiple patterns, returning bindings that satisfy all patterns.
   Patterns can share variables for joins.

   Example:
     (query-claims [['(project-name ?pid ?name)
                     '(surrogate ?pid)]]
                   :sur-craft-beer)
     => [{?pid :sur-craft-beer, ?name \"Test\"}]"
  [pid patterns]

  (let [claims (get-claims pid)
        ;; For each pattern, find all matching claims and their bindings
        pattern-results (for [pattern patterns]
                          (for [claim claims
                                :let [bindings (try (u/unify pattern claim)
                                                    (catch Exception _ nil))]
                                :when bindings]
                            bindings))]
    ;; Find bindings that work for all patterns (join on shared variables)
    (when (every? seq pattern-results)
      (let [first-results (first pattern-results)
            rest-results (rest pattern-results)]
        (vec (for [base-bindings first-results
                   :when (every? (fn [results]
                                   (some (fn [other-bindings]
                                           ;; Check if bindings are compatible
                                           (every? (fn [[var val]]
                                                     (or (not (contains? other-bindings var))
                                                         (= val (get other-bindings var))))
                                                   base-bindings))
                                         results))
                                 rest-results)]
               ;; Merge all compatible bindings
               (reduce (fn [acc results]
                         (merge acc (first (filter (fn [other]
                                                     (every? (fn [[var val]]
                                                               (or (not (contains? other var))
                                                                   (= val (get other var))))
                                                             acc))
                                                   results))))
                       base-bindings
                       rest-results)))))))

;;; -------------------------- Starting and stopping ------------------------------
(defn register-project-dbs!
  "Register all project databases from the system database"
  []
  (doseq [id (sdb/list-projects)]
    (let [proj-cfg (sutil/db-cfg-map {:type :project :id id})]
      (when (d/database-exists? proj-cfg)
        (log! :info (str "Registering project database: " id))
        (sutil/register-db id proj-cfg)))))

(defn init-all-dbs!
  "Initialize system database and register all project databases"
  []
  (log! :info "Initializing all databases...")

  ;; Ensure system database exists
  (sdb/ensure-system-db!)

  ;; Register all project databases
  (register-project-dbs!)

  {:system-db (sutil/db-cfg-map {:type :system :id :system})
   :project-count (count (sdb/list-projects))})

;; Mount defstate for automatic database initialization
(defstate system-and-project-dbs
  :start (init-all-dbs!)
  :stop (log! :info "Shutting down database connections..."))
