(ns sched-mcp.project-db
  "Database functions for projects - includes backup/restore for schema migrations"
  (:require
   [clojure.edn         :as edn]
   [clojure.java.io     :as io]
   [clojure.pprint      :refer [pprint]]
   [clojure.string      :as str]
   [datahike.api        :as d]
   [datahike.pull-api   :as dp]
   [mount.core          :as mount  :refer [defstate]]
   [sched-mcp.schema    :as schema :refer [project-schema-key?]]
   [sched-mcp.sutil     :as sutil  :refer [connect-atm mocking? resolve-db-id shadow-pid]]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util      :as util :refer [log! now]]))

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
   Return the :message/id.
   Note that this doesn't handle :message/answers-question. That is typically done with update-msg."
  [{:keys [pid cid from content table code tags question-type pursuing-DS]}]
  (assert (keyword? cid))
  (assert (#{:system :human :surrogate :developer-injected} from))
  (assert (string? content))
  (assert (not= content "null"))
  (if-let [conn (connect-atm pid)]
    (let [msg-id (inc (max-msg-id pid))
          pursuing-DS (or pursuing-DS (get-active-DS-id pid cid))]
      (d/transact conn {:tx-data [{:db/id (conversation-exists? pid cid)
                                   :conversation/messages (cond-> #:message{:id msg-id :from from :time (now) :content content}
                                                            table (assoc :message/table (str table))
                                                            (not-empty tags) (assoc :message/tags tags)
                                                            question-type (assoc :message/question-type question-type)
                                                            code          (assoc :message/code code)
                                                            pursuing-DS (assoc :message/pursuing-DS pursuing-DS))}]})
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
S                   [?c-ent :conversation/messages ?m-ent]
                   [?m-ent :message/id ?mid]]
                 conn cid mid)]
    (dp/pull conn '[*] eid)))

(defn update-msg!
  "Update the message with given info (a merge)."
  [pid cid mid {:message/keys [answers-question graph--orm] :as info}]
  (if-let [eid (message-exists? pid cid mid)]
    (do (when answers-question
          (if (= mid answers-question)
            (throw (ex-info "Attempting to mark a message as answer the question it raises." {:pid pid :cid cid :mid mid}))
            (d/transact (connect-atm pid) {:tx-data [(merge {:db/id eid} info)]})))
        (when graph--orm
          (d/transact (connect-atm pid) {:tx-data [(merge {:db/id eid} info)]})))
    (log! :warn (str "Could not find msg for update-msg: pid = " pid " cid = " cid " mid = " mid))))

;;; --------------------------------------- Conversations ---------------------------------------------------------------------
(def conversation-intros
  {:process
   (str "This is where we discuss how product gets made, or in the cases of services, how the service gets delivered. "
        "It is also where we introduce MiniZinc, the <a href=\"terms/dsl\">domain specific language</a> (DSL) "
        "through which together we design a solution to your scheduling problem. "
        "You can read more about <a href=\"about/process-conversation\">how this works</a>.")
   :data
   (str "This is where we ask you to talk about the data that drives your decisions (customer orders, due dates, worker schedules,... whatever). "
        "Here you can either upload actual data as spreadsheets, or we can talk about the kinds of information you use in general terms and "
        "we can invent some similar data to run demonstrations. "
        "Whenever someone suggests that you upload information to them, you should be cautious. "
        "Read more about the intent of this conversation and the risks of uploading data <a href=\"about/uploading-data\">here</a>.")
   :resources
   (str "This is typically the third conversation we'll have, after discussing process and data. "
        "(By the way, you can always go back to a conversation and add to it.) "
        "You might have already mentioned the resources (people, machines) by which you make product or deliver services. "
        "Here we try to integrate this into the MiniZinc solution. Until we do that, we won't be able to generate realistic schedules.")
   :optimality
   (str "This is where we discuss what you intend by 'good' and 'ideal' schedules. "
        "With these we formulate an objective and model it in MiniZinc. "
        "The MiniZinc solution can change substantially owing to this discussion, but owing to all the work we did "
        "to define requirements, we think it will be successful.")})

(defn add-conversation-intros
  "Add an intro describing the topic and rationale of the conversation."
  [pid]
  (doseq [cid [:process :data :resources :optimality]]
    (add-msg! {:pid pid :cid cid :from :system :content (get conversation-intros cid) :tags [:conversation-intro]})))

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
(defn delete-project!
  "Mark a project as deleted (soft delete)"
  [pid]
  (when-let [conn (connect-atm :system)]
    (let [pid (keyword pid)]
      (d/transact conn
                  [{:project/id pid
                    :project/status :deleted}])
      {:pid pid
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

(defn unique-pid
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

(defn add-project-to-system
  "Add the argument project (a db-cfg map) to the system database."
  [id project-name dir]
  (let [conn-atm (connect-atm :system)
        eid (d/q '[:find ?eid . :where [?eid :system/name "SYSTEM"]] @conn-atm)]
    (d/transact conn-atm {:tx-data [{:db/id eid
                                     :system/projects {:project/id id
                                                       :project/name project-name
                                                       :project/dir dir}}]})))

(defn delete-project-db!
  "Delete an existing project database if it exists"
  [pid]
  (let [pid (keyword pid)
        cfg (sutil/db-cfg-map {:type :project :id pid})]
    (when (d/database-exists? cfg)
      (log! :info (str "Deleting existing database for: " pid))
      (d/delete-database cfg))))

(def conversation-defaults
  [{:conversation/id :process
    :conversation/active-DS-id :process/warm-up-with-challenges  ; We assume things start here.
    :conversation/status :in-progress}                           ; We assume things start here.
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
   Returns a unique PID (might not be same as the argument)."
  [{:keys [pid project-name _domain cid force-replace? in-mem? additional-info]
    :or {project-name "Unnamed project"
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
       (add-project-to-system pid project-name dir))
     (when (d/database-exists? cfg) (d/delete-database cfg))
     (d/create-database cfg)
     (sutil/register-db pid cfg)
     ;; Add to project db
     (d/transact (connect-atm pid) schema/db-schema-proj)
     (d/transact (connect-atm pid) {:tx-data [{:project/id pid
                                               :project/name project-name
                                               :project/execution-status :running
                                               :project/active-conversation :process
                                               :project/claims [{:claim/string (str `(~'project-id ~pid))}
                                                                {:claim/string (str `(~'project-name ~pid ~project-name))}]
                                               :project/conversations conversation-defaults}]})
     (add-conversation-intros pid)
     (when (not-empty additional-info)
       (d/transact (connect-atm pid) additional-info))
     ;; Add knowledge of this project to the system db.
     (log! :info (str "Created project database for " pid))
     {:pid pid
      :cid cid
      :status :created}))

(defn ^:admin archive-project!
  "Archive a project"
  [pid]
  (when-let [conn (connect-atm :system)]
    (d/transact conn
                [{:project/id pid
                  :project/status :archived}])
    {:pid pid
     :status :archived}))

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
                           (let [{:keys [data-structure]} (edn/read-string s)]
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

;;; This is what is in orchestration-ignore.clj:
#_(defn get-interview-progress
  "Get overall interview progress across all DS"
  [pid]
  (let [completed (get-completed-ds pid)
        completed-ids (set (map :ds-id completed))
        ;; Estimate total DS needed (varies by problem type)
        estimated-total (cond
                          (contains? completed-ids :process/timetabling) 4
                          (contains? completed-ids :process/job-shop) 5
                          (contains? completed-ids :process/flow-shop) 4
                          :else 3)
        progress-pct (int (* 100 (/ (count completed) estimated-total)))]

    {:completed-ds completed-ids
     :completed-count (count completed)
     :estimated-total estimated-total
     :progress-percentage progress-pct
     :current-phase (cond
                      (empty? completed) :warm-up
                      (< (count completed) 2) :process-discovery
                      (< (count completed) 4) :data-modeling
                      :else :optimization-setup)}))

(defn get-interview-progress
  "Get overall interview progress across all DS"
  [pid]
  :not-yet-implemented)

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
   returns the value of :dstruct/budget-left for the given ds-id.
   This will create the summary data structure if it doesn't exist."
  [pid ds-id]
  (assert (or (nil? ds-id) ((sdb/system-DS?) ds-id)))
  (if-let [eid (ASCR-exists? pid ds-id)]
    (d/q '[:find ?left . :in $ ?eid :where [?eid :dstruct/budget-left ?left]] @(connect-atm pid) eid)
    (do (when ds-id (init-ASCR! pid ds-id))
        1.0)))

(def default-DS-budget-decrement 0.05)

(defn reduce-questioning-budget!
  "The system stores DS-instructions that may or may not have an :DS/budget-decrement.
   If it does, decrement the same-named summary structure objec by that value.
   Otherwise, decrement the same-named object by the db/default-DS-budget-decrement."
  [pid ds-id ]
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
                   [?eid :dstruct/id ?ds-id]]
                 @(connect-atm pid) ds-id)]
    (if eid
      (d/transact (connect-atm pid) {:tx-data [{:db/id eid :dstruct/budget-left (- val dec-val)}]})
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
  (cond (map? obj)       (reduce-kv (fn [m k v] (if (#{"x" "y"} k)
                                                  (assoc m (keyword k) v)
                                                  (assoc m k (key-xy v))))
                                    {}
                                    obj)
        (vector? obj)    (mapv key-xy obj)
        :else            obj))

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
  (doseq [pid (list-projects)]
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
  (doseq [pid (list-projects)]
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
  (log! :info (str "Recreating these projects: " (list-projects)))
  (doseq [pid (list-projects)]
    (recreate-project-db! pid)))

;;; -------------------------- Starting and stopping ------------------------------
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
  (sdb/ensure-system-db!)

  ;; Register all project databases
  (register-project-dbs!)

  {:system-db (sutil/db-cfg-map {:type :system :id :system})
   :project-count (count (list-projects))})

;; Mount defstate for automatic database initialization
(defstate system-and-project-dbs
  :start (init-all-dbs!)
  :stop (log! :info "Shutting down database connections..."))
