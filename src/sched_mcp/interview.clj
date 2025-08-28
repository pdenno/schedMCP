(ns sched-mcp.interview
  "Interview management for scheduling domain"
  (:require
   [clojure.string :as str]
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [db-cfg-map register-db connect-atm datahike-schema]]
   [sched-mcp.util :as util :refer [log!]]
   [sched-mcp.warm-up :as warm-up]))

(def ^:diag diag (atom nil))

;;; Simple schema for now - we'll expand this
(def interview-schema
  [{:db/ident :project/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   {:db/ident :project/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :project/domain
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :project/created
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :conversation/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   {:db/ident :conversation/project
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :conversation/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :conversation/current-eads
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   {:db/ident :message/conversation
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/from
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :message/timestamp
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :eads/data
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :eads/structure
    :db/valueType :db.type/string ; EDN string for now
    :db/cardinality :db.cardinality/one}])

(defn create-project-db! [project-id project-name domain]
  "Create a new project database"
  (let [cfg (db-cfg-map {:type :project :id project-id})
        db-exists? (try (d/database-exists? cfg) (catch Exception _ false))]
    (when-not db-exists?
      ;; Create directories if needed
      (when-let [path (-> cfg :store :path)]
        (-> path java.io.File. .mkdirs))
      (d/create-database cfg))
    (register-db project-id cfg)
    (let [conn (connect-atm project-id)]
      ;; Add schema
      (d/transact conn interview-schema)
      ;; Add initial project data
      (d/transact conn [{:project/id project-id
                         :project/name project-name
                         :project/domain (or domain "manufacturing")
                         :project/created (java.util.Date.)}])
      project-id)))

(defn start-interview [project-name domain]
  "Start a new interview session"
  (try
    (let [project-id (keyword (str/lower-case (str/replace project-name #"\s+" "-")))
          _ (create-project-db! project-id project-name domain)
          conn (connect-atm project-id)
          conversation-id (keyword (str "conv-" (System/currentTimeMillis)))

          ;; Get project entity
          project-eid (d/q '[:find ?e .
                             :where [?e :project/id ?pid]]
                           @conn)

          ;; Create conversation
          _ (d/transact conn [{:conversation/id conversation-id
                               :conversation/project project-eid
                               :conversation/status :active
                               :conversation/current-eads :process/warm-up-with-challenges}])

          ;; Initialize warm-up EADS
          warm-up-data (warm-up/init-warm-up project-id conversation-id)
          first-question (warm-up/get-next-question project-id conversation-id)]

      {:project-id project-id
       :conversation-id conversation-id
       :message (str "Starting scheduling interview for " project-name
                     (when domain (str " in " domain " domain")))
       :next-question first-question})

    (catch Exception e
      (log! :error (str "Failed to start interview: " (.getMessage e)))
      {:error (str "Failed to start interview: " (.getMessage e))})))

(defn get-interview-context [project-id]
  "Get current interview context and state"
  (try
    (let [conn (connect-atm project-id)
          ;; Get active conversation
          conv-data (d/q '[:find ?cid ?status ?eads
                           :where
                           [?c :conversation/id ?cid]
                           [?c :conversation/status ?status]
                           [?c :conversation/current-eads ?eads]]
                         @conn)]
      (if-let [[cid status eads] (first conv-data)]
        {:conversation-id cid
         :status status
         :current-eads eads
         :progress (warm-up/get-progress project-id cid)
         :next-question (when (= status :active)
                          (warm-up/get-next-question project-id cid))}
        {:error "No active conversation found"}))
    (catch Exception e
      (log! :error (str "Failed to get context: " (.getMessage e)))
      {:error (str "Failed to get context: " (.getMessage e))})))

(defn submit-answer [project-id conversation-id answer question-id]
  "Submit an answer to the current question"
  (try
    (let [conn (connect-atm project-id)
          message-id (keyword (str "msg-" (System/currentTimeMillis)))

          ;; Store the message
          _ (d/transact conn [{:message/id message-id
                               :message/conversation [:conversation/id conversation-id]
                               :message/from :user
                               :message/content answer
                               :message/timestamp (java.util.Date.)}])

          ;; Process the answer
          result (warm-up/process-answer project-id conversation-id answer question-id)

          ;; Get next question or check if complete
          next-q (when-not (:complete? result)
                   (warm-up/get-next-question project-id conversation-id))]

      (merge result
             {:message-id message-id
              :next-question next-q}))

    (catch Exception e
      (log! :error (str "Failed to submit answer: " (.getMessage e)))
      {:error (str "Failed to submit answer: " (.getMessage e))})))
