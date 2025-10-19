(ns sched-mcp.tools.iviewr-tools
  "Interview management tools for scheduling domain"
  (:require
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :refer [log!]]))

(def ^:diag diag (atom nil))

;;; Tool specifications following clojure-mcp pattern

(defn start-interview-tool
  "Tool function to start a new scheduling interview"
  [{:keys [project_name domain]}]
  (log! :info (str "MCP Tool: start-interview called with project_name=" project_name ", domain=" domain))
  (try
    (let [result :deprecated #_(iview/start-interview project_name domain)]
      (log! :info (str "MCP Tool: start-interview result: " (pr-str result)))
      (if (:error result)
        {:error (:error result)}
        {:success true
         :project_id (name (:project-id result))
         :conversation_id (name (:conversation-id result))
         :message (:message result)
         :next_question (when-let [q (:next-question result)]
                          {:id (name (:id q))
                           :text (:text q)
                           :help (:help q)
                           :required (:required q)})}))
    (catch Exception e
      (log! :error (str "MCP Tool: start-interview error: " (.getMessage e)))
      {:error (str "Failed to start interview: " (.getMessage e))})))

(def start-interview-tool-spec
  {:name "start_interview"
   :description "Start a new scheduling interview session. This creates a project and initializes the interview process with warm-up questions about scheduling challenges."
   :schema {:type "object"
            :properties {:project_name {:type "string"
                                        :description "Name for the scheduling project (e.g., 'Craft Beer Brewery', 'Ice Cream Production')"}
                         :domain {:type "string"
                                  :description "Manufacturing domain (optional, e.g., 'food-processing', 'metalworking', 'textiles')"}}
            :required ["project_name"]}
   :tool-fn start-interview-tool})

(defn get-interview-context-tool
  "Get current interview context"
  [{:keys [project_id]}]
  (try
    (if-not project_id
      {:error "No project_id provided"}
      (let [pid (keyword project_id)
            context :deprecated #_(iview/get-interview-context pid)]
        (if (:error context)
          context
          {:conversation_id (name (:conversation-id context))
           :status (name (:status context))
           :current_phase (name (:current-ds context))
           :progress (:progress context)
           :next_question (when-let [q (:next-question context)]
                            {:id (name (:id q))
                             :text (:text q)
                             :help (:help q)
                             :required (:required q)})})))
    (catch Exception e
      {:error (str "Failed to get context: " (.getMessage e))})))

(def get-interview-context-tool-spec
  {:name "get_interview_context"
   :description "Get the current interview context including next questions to ask and progress status. Use this to understand what phase the interview is in and what to ask next."
   :schema {:type "object"
            :properties {:project_id {:type "string"
                                      :description "The project ID returned from start_interview"}}
            :required ["project_id"]}
   :tool-fn get-interview-context-tool})

(defn submit-response-tool
  "Submit an response to current question"
  [{:keys [project_id conversation_id response question_id]}]
  (log! :info (str "MCP Tool: submit-response called with project_id=" project_id
                   ", conversation_id=" conversation_id
                   ", question_id=" question_id
                   ", response=" (subs response 0 (min 50 (count response))) "..."))
  (try
    (cond
      (not project_id) {:error "No project_id provided"}
      (not conversation_id) {:error "No conversation_id provided"}
      (not response) {:error "No response provided"}
      :else
      (let [pid (keyword project_id)
            cid (keyword conversation_id)
            ;; If no question_id provided, find the last unanswered question
            qid (or question_id
                    (pdb/most-recent-unanswered pid cid))
            _ (log! :info (str "MCP Tool: submit-response using question_id=" qid))
            result (if qid
                     :deprecated #_(iview/submit-response pid cid response qid)
                     {:error "No current unanswered question found"})]
        (log! :info (str "MCP Tool: submit-response result: " (pr-str result)))
        (if (:error result)
          result
          {:success true
           :ds-complete (:ds-complete result)
           :budget-remaining (:budget-remaining result)
           :message-id (:message-id result)
           :next-step (:next-step result)})))
    (catch Exception e
      (log! :error (str "MCP Tool: submit-response error: " (.getMessage e)))
      {:error (str "Failed to submit response: " (.getMessage e))})))

(def submit-response-tool-spec
  {:name "submit_response"
   :description "Submit an response to the current interview question. The system will process the response and provide the next question if available."
   :schema {:type "object"
            :properties {:project_id {:type "string"
                                      :description "The project ID"}
                         :conversation_id {:type "string"
                                           :description "The conversation ID"}
                         :response {:type "string"
                                    :description "The user's response to the current question"}
                         :question_id {:type "string"
                                       :description "ID of the question being responseed (optional, uses current question if not provided)"}}
            :required ["project_id" "conversation_id" "response"]}
   :tool-fn submit-response-tool})

(defn get-interview-responses-tool
  "Get all responses collected so far"
  [{:keys [project_id conversation_id]}]
  (try
    (cond
      (not project_id) {:error "No project_id provided"}
      (not conversation_id) {:error "No conversation_id provided"}
      :else
      (let [pid (keyword project_id)
            cid (keyword conversation_id)
            {:conversation/keys [messages status] :as ds-data}
            (pdb/get-conversation pid cid)]
        (if ds-data
          {:phase (name (get ds-data :phase :unknown))
           :complete? (= status :ds-exhausted)
           :responses messages} ; ToDo: Actually this has the question too!
          {:phase "unknown"
           :complete? false
           :responses {}
           :message "No interview data found yet"})))
    (catch Exception e
      {:error (str "Failed to get responses: " (.getMessage e))})))

(def get-interview-responses-tool-spec
  {:name "get_interview_responses"
   :description "Get all responses collected so far in the interview. Useful for reviewing what information has been gathered."
   :schema {:type "object"
            :properties {:project_id {:type "string"
                                      :description "The project ID"}
                         :conversation_id {:type "string"
                                           :description "The conversation ID"}}
            :required ["project_id" "conversation_id"]}
   :tool-fn get-interview-responses-tool})

;;; Export all tool specs for use with MCP
(def tool-specs
  [start-interview-tool-spec
   get-interview-context-tool-spec
   submit-response-tool-spec
   get-interview-responses-tool-spec])
