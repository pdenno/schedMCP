(ns sched-mcp.interview
  "Interview management for scheduling domain"
  (:require
   [clojure.string :as str]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.tools.orch.ds-util :as dsu]
   [sched-mcp.util :as util :refer [log!]]))

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
  (let [pid (-> project-name (str/replace #"\s+" "-") str/lower-case keyword)
        project-result (pdb/create-project-db! {:pid pid :project-name project-name})
        pid (:pid project-result)
        cid :process ; Starting conversation
        ;; The first DS is already set by create-project-db! as :process/warm-up-with-challenges
        first-ds :process/warm-up-with-challenges]
    {:project-id (name pid)
     :conversation-id (name cid)
     :message (str "Starting scheduling interview for " project-name
                   (when domain (str " in " domain " domain")))
     :status "created"
     :current-ds (name first-ds)
     :next-step "Use iviewr_formulate_question to generate the first question"}))

(defn get-interview-context
  "Get current interview context and state"
  [project-id]
  (try
    (let [pid (keyword project-id)
          cid (pdb/get-active-cid pid)
          conv-data (pdb/get-conversation pid cid)
          active-ds-id (pdb/get-active-DS-id pid cid)
          ;; Get interview progress
          progress (pdb/get-interview-progress pid)]
      (if conv-data
        {:conversation-id (name cid)
         :status (:conversation/status conv-data)
         :current-ds (when active-ds-id (name active-ds-id))
         :progress progress
         :message-count (count (:conversation/messages conv-data))
         :next-step (if active-ds-id
                      "Use iviewr_formulate_question to generate next question"
                      "Use orch_get_next_ds to select a Discovery Schema")}
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
          ;; Add the response message
          mid (pdb/add-msg! {:pid pid
                             :cid cid
                             :content response
                             :from :user
                             :pursuing-DS ds-id})
          ;; Link to question if provided
          _ (when question-id
              (pdb/update-msg! pid cid mid
                               {:message/answers-question (Long/parseLong (str question-id))}))
          ;; Check if DS is complete after this response
          ;; (The actual SCR extraction and ASCR update happens in interpret-response)
          complete? (when ds-id (dsu/ds-complete? ds-id pid))
          budget-left (when ds-id (pdb/get-questioning-budget-left! pid ds-id))]
      {:success true
       :message-id mid
       :ds-complete complete?
       :budget-remaining budget-left
       :next-step (if complete?
                    "DS complete - use orch_complete_ds then orch_get_next_ds"
                    "Use iviewr_interpret_response to extract SCR, then iviewr_formulate_question for next question")})
    (catch Exception e
      (log! :error (str "Failed to submit answer: " (.getMessage e)))
      {:error (str "Failed to submit answer: " (.getMessage e))})))
