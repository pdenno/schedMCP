(ns sched-mcp.interview
  "Interview management for scheduling domain"
  (:require
   [clojure.string       :as str]
   [datahike.api         :as d]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.sutil      :as sutil :refer [db-cfg-map register-db connect-atm datahike-schema]]
   [sched-mcp.util       :as util :refer [log!]]))

(def ^:diag diag (atom nil))

;;; This must NOT use 'warm-up'!
;;; ToDo: This is messed-up; it should be given the cid. There are four conversations to be had. #{:process :data :resources :optimality}.
(defn start-interview
  "Start a new interview session by starting a project.
   Projects start with:
        :conversation/active-DS-id = :process/warm-up-with-challenges (in the process conversation)
        :project/active-conversation = :process

  This should cause the orchestrator to choose :process/warm-up-with-challenges (because there is no ASCR).
  "
  [project-name domain]
  (let [project-id (-> project-name (str/replace #"\s+" "-") str/lower-case keyword)
        pid (-> (pdb/create-project-db! {:pid project-id :project-name project-name}) :pid)]
    {:project-id (name pid)
     :conversation-id "process"
     :message (str "Starting scheduling interview for " project-name
                   (when domain (str " in " domain " domain")))
     :next-question :not-yet-implemented})) ; <==================================================

(defn get-interview-context
  "Get current interview context and state"
  [project-id]
  (try
    (let [pid (keyword project-id)
          cid (pdb/get-active-cid pid)
          {:conversation/keys [active-DS-id status messages] :as conv-data}
          (pdb/get-conversation pid cid)]
      (if-let [[cid status ds] (first conv-data)]
        {:conversation-id cid
         :status status
         :current-ds active-DS-id
         :progress :not-yet-implemented ; Maybe make things so that it isn't needed! ; <==================================================
         :next-question :not-yet-implemented} ; <==================================================
        {:error "No active conversation found"}))
    (catch Exception e
      (log! :error (str "Failed to get context: " (.getMessage e)))
      {:error (str "Failed to get context: " (.getMessage e))})))

(defn submit-response
  "Submit an answer to the current question"
  [project-id conversation-id response question-id]
  (try
    (let [pid (keyword project-id)
          cid (keyword conversation-id)
          ds-id (pdb/get-active-DS-id pid cid)
          mid (pdb/add-msg! {:pid pid :cid cid :content response :from :surrogate :pursuing-DS ds-id})
          _ (pdb/update-msg! pid cid mid {:message/answers-question question-id})
          ;; Process the answer
          result {:not-yet-implemented :not-yet-implemented}
          ;; (warm-up/process-answer project-id conversation-id answer question-id)

          ;; Get next question or check if complete
          next-q :not-yet-implemented ; <==================================================
          ;;(when-not (:complete? result)
          ;;     (warm-up/get-next-question project-id conversation-id))
          ]
      (merge result
             {:message-id mid
              :next-question next-q}))
    (catch Exception e
      (log! :error (str "Failed to submit answer: " (.getMessage e)))
      {:error (str "Failed to submit answer: " (.getMessage e))})))
