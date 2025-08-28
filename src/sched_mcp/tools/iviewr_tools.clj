(ns sched-mcp.tools.iviewr-tools
  "Interview management tools for scheduling domain"
  (:require
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [db-cfg-map register-db connect-atm datahike-schema]]
   [sched-mcp.util :as util :refer [log!]]
   [sched-mcp.warm-up :as warm-up]
   [sched-mcp.interview :as interview]))

(def ^:diag diag (atom nil))

;;; Tool specifications following clojure-mcp pattern

(defn start-interview-tool
  "Tool function to start a new scheduling interview"
  [{:keys [project_name domain]}]
  (log! :info (str "MCP Tool: start-interview called with project_name=" project_name ", domain=" domain))
  (try
    (let [result (interview/start-interview project_name domain)]
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
      (log! :info (str "MCP Tool: start-interview error: " (.getMessage e)) {:level :error})
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
            context (interview/get-interview-context pid)]
        (if (:error context)
          context
          {:conversation_id (name (:conversation-id context))
           :status (name (:status context))
           :current_phase (name (:current-eads context))
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

(defn submit-answer-tool
  "Submit an answer to current question"
  [{:keys [project_id conversation_id answer question_id]}]
  (log! :info (str "MCP Tool: submit-answer called with project_id=" project_id
              ", conversation_id=" conversation_id
              ", question_id=" question_id
              ", answer=" (subs answer 0 (min 50 (count answer))) "..."))
  (try
    (cond
      (not project_id) {:error "No project_id provided"}
      (not conversation_id) {:error "No conversation_id provided"}
      (not answer) {:error "No answer provided"}
      :else
      (let [pid (keyword project_id)
            cid (keyword conversation_id)
            ;; If no question_id provided, get the current question
            current-q (when-not question_id
                        (warm-up/get-next-question pid cid))
            qid (or question_id
                    (when current-q (name (:id current-q))))
            _ (log! :info (str "MCP Tool: submit-answer using question_id=" qid))
            result (if qid
                     (interview/submit-answer pid cid answer qid)
                     {:error "No current question to answer"})]
        (log! :info (str "MCP Tool: submit-answer result: " (pr-str result)))
        (if (:error result)
          result
          {:success true
           :complete? (:complete? result)
           :progress (:progress result)
           :next_question (when-let [q (:next-question result)]
                            {:id (name (:id q))
                             :text (:text q)
                             :help (:help q)
                             :required (:required q)})})))
    (catch Exception e
      (log! :info (str "MCP Tool: submit-answer error: " (.getMessage e)) {:level :error})
      {:error (str "Failed to submit answer: " (.getMessage e))})))

(def submit-answer-tool-spec
  {:name "submit_answer"
   :description "Submit an answer to the current interview question. The system will process the answer and provide the next question if available."
   :schema {:type "object"
            :properties {:project_id {:type "string"
                                      :description "The project ID"}
                         :conversation_id {:type "string"
                                           :description "The conversation ID"}
                         :answer {:type "string"
                                  :description "The user's answer to the current question"}
                         :question_id {:type "string"
                                       :description "ID of the question being answered (optional, uses current question if not provided)"}}
            :required ["project_id" "conversation_id" "answer"]}
   :tool-fn submit-answer-tool})

(defn get-interview-answers-tool
  "Get all answers collected so far"
  [{:keys [project_id conversation_id]}]
  (try
    (cond
      (not project_id) {:error "No project_id provided"}
      (not conversation_id) {:error "No conversation_id provided"}
      :else
      (let [pid (keyword project_id)
            cid (keyword conversation_id)
            eads-data (warm-up/get-eads-data pid cid)]
        (if eads-data
          {:phase (name (get eads-data :phase :unknown))
           :complete? (get eads-data :complete? false)
           :answers (reduce-kv (fn [m k v]
                                 (assoc m (name k) v))
                               {}
                               (get eads-data :answers {}))}
          {:phase "unknown"
           :complete? false
           :answers {}
           :message "No interview data found yet"})))
    (catch Exception e
      {:error (str "Failed to get answers: " (.getMessage e))})))

(def get-interview-answers-tool-spec
  {:name "get_interview_answers"
   :description "Get all answers collected so far in the interview. Useful for reviewing what information has been gathered."
   :schema {:type "object"
            :properties {:project_id {:type "string"
                                      :description "The project ID"}
                         :conversation_id {:type "string"
                                           :description "The conversation ID"}}
            :required ["project_id" "conversation_id"]}
   :tool-fn get-interview-answers-tool})

;;; Export all tool specs for use with clojure-mcp
(def tool-specs
  [start-interview-tool-spec
   get-interview-context-tool-spec
   submit-answer-tool-spec
   get-interview-answers-tool-spec])
