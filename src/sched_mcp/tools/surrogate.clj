(ns sched-mcp.tools.surrogate
  "MCP tools for surrogate expert functionality"
  (:require
   [sched-mcp.surrogate :as surrogate]))

;;; Tool definitions for surrogate expert

(def start-surrogate-tool-spec
  {:name "start_surrogate_expert"
   :description (str "Start an interview with a surrogate expert agent that simulates "
                     "a domain expert in manufacturing. The expert will answer questions "
                     "about their manufacturing processes, challenges, and scheduling needs.")
   :schema
   {:type "object"
    :properties
    {:domain {:type "string"
              :description "Manufacturing domain for the surrogate expert (e.g., craft-beer, plate-glass, metal-fabrication, textiles, food-processing, etc.)"}
     :company_name {:type "string"
                    :description "Name of the simulated company (optional)"}
     :project_name {:type "string"
                    :description "Name for the interview project (optional)"}}
    :required ["domain"]}

   :tool-fn
   (fn [{:keys [domain company_name project_name]}]
     (try
       (let [result (surrogate/start-surrogate-interview
                     {:domain (keyword domain)
                      :company-name company_name
                      :project-name project_name})]
         ;; Format for display with orange color indication
         (str "🟠 " (:message result) "\n"
              "Project ID: " (:project-id result) "\n"
              "Expert ID: " (:expert-id result)))
       (catch Exception e
         (str "Error starting surrogate expert: " (.getMessage e)))))})

(def answer-question-tool-spec
  {:name "surrogate_answer"
   :description (str "Get an answer from the surrogate expert. The expert will respond "
                     "as a domain expert would, providing specific details about their "
                     "manufacturing processes and challenges.")
   :schema
   {:type "object"
    :properties
    {:project_id {:type "string"
                  :description "Project ID from start_surrogate_expert"}
     :question {:type "string"
                :description "Question to ask the surrogate expert"}}
    :required ["project_id" "question"]}

   :tool-fn
   (fn [{:keys [project_id question]}]
     (try
       (let [result (surrogate/surrogate-answer-question
                     {:project-id project_id
                      :question question})]
         (if (:error result)
           (:error result)
           ;; Format response with orange indicator
           (str "🟠 Expert Response:\n" (:response result))))
       (catch Exception e
         (str "Error getting response: " (.getMessage e)))))})

(def get-session-tool-spec
  {:name "get_surrogate_session"
   :description "Get the current state of a surrogate expert session for debugging"
   :schema
   {:type "object"
    :properties
    {:project_id {:type "string"
                  :description "Project ID to inspect"}}
    :required ["project_id"]}

   :tool-fn
   (fn [{:keys [project_id]}]
     (try
       (if-let [session (surrogate/get-surrogate-session project_id)]
         (str "Session for " project_id ":\n"
              "Expert: " (get-in session [:expert-persona :company-name]) "\n"
              "Domain: " (get-in session [:expert-persona :domain]) "\n"
              "Products: " (clojure.string/join ", "
                                                (get-in session [:expert-persona :products])) "\n"
              "Conversation history: " (count (:conversation-history session)) " exchanges")
         "No session found")
       (catch Exception e
         (str "Error: " (.getMessage e)))))})

(def list-sessions-tool-spec
  {:name "list_surrogate_sessions"
   :description "List all active surrogate expert sessions"
   :schema
   {:type "object"
    :properties {}
    :required []}

   :tool-fn
   (fn [_]
     (try
       (let [sessions (surrogate/list-surrogate-sessions)]
         (if (empty? sessions)
           "No active surrogate sessions"
           (str "Active surrogate sessions:\n"
                (clojure.string/join "\n" sessions))))
       (catch Exception e
         (str "Error: " (.getMessage e)))))})

;;; Collect all surrogate tools
(def tool-specs
  [start-surrogate-tool-spec
   answer-question-tool-spec
   get-session-tool-spec
   list-sessions-tool-spec])
