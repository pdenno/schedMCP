(ns sched-mcp.surrogate
  "Surrogate expert agent implementation for automated interview testing"
  (:require
   [clojure.string       :as str]
   [datahike.api         :as d]
   [sched-mcp.llm        :as llm]
   [sched-mcp.sutil      :as sutil :refer [connect-atm]]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util       :as util :refer [log!]]
   [taoensso.telemere    :as tel]))

;;; System instruction components

(def table-handling-instructions
  "Instructions for how the surrogate should handle table requests"
  (str "Typically you answer in sentences. However, the interviewers may ask you provide a table or add information to a table that the interviewer provides.\n"
       "In these cases, respond with an HTML table wrapped in #+begin_src HTML ... #+end_src\n"
       "All the tables you generate/complete should have the header as the first table row.\n"
       "For example if you were asked to generate/complete a table about process durations for process steps you might respond with:\n"
       "\"Here are my estimates of the step durations. Note that I added 'Quality Check' which I think should be included in this context:\"\n\n"
       "#+begin_src HTML\n"
       "<table>\n"
       "  <tr><th>Process Step</th>                <th>Duration</th></tr>\n"
       "  <tr><td>Mashing</td>                     <td>90 minutes</td></tr>\n"
       "  <tr><td>Boiling</td>                     <td>60 minutes</td></tr>\n"
       "  <tr><td>Quality Check</td>               <td>15 minutes</td></tr>\n"
       "</table>\n"
       "#+end_src"))

(defn system-instruction
  "Generate the system instruction for a surrogate expert in a specific domain"
  [domain company-name]
  (str
   (format "You are the production manager at %s, a company that specializes in %s.\n"
           company-name (name domain))
   "You are an expert in production and manage your company's supply chains.\n"
   "You help by answering an interviewer's questions that will allow us to collaborate in building a scheduling system for your company.\n\n"

   "Currently, you do not use any software in scheduling your work, except maybe spreadsheets and office software calendars.\n"
   "Your answers typically are short, just a few sentences each.\n"
   "Be specific and concrete in your answers, using actual numbers, times, and quantities when appropriate.\n"
   "If asked about challenges, mention realistic scheduling difficulties your company faces.\n"
   "Stay consistent with any facts, processes, or details you've already mentioned.\n\n"

   table-handling-instructions))

;;; Expert persona creation - simplified

(defn create-expert-persona
  "Create a minimal persona for the surrogate expert"
  [{:keys [domain company-name]
    :or {company-name nil}}]
  (let [company-name (or company-name
                         (str "Acme " (str/capitalize (name domain)) " Company"))]
    {:expert-id (keyword (str "expert-" (System/currentTimeMillis)))
     :domain domain
     :company-name company-name
     :created-at (util/now)}))

;;; Conversation state management

;;;   "Active expert sessions indexed by project-id"
(defonce expert-sessions-atom
  (atom {}))

(defn init-expert-session
  "Initialize a new expert session"
  [project-id expert-persona & {:keys [conversation-id]}]
  (let [session {:expert-persona expert-persona
                 :conversation-history []
                 :session-start (util/now)
                 :conversation-id conversation-id}]
    (swap! expert-sessions-atom assoc project-id session)
    session))

(defn update-session-history
  "Add a Q&A pair to the session history"
  [project-id question answer]
  (swap! expert-sessions-atom update-in
         [project-id :conversation-history]
         conj {:question question
               :answer answer
               :timestamp (util/now)}))

(defn persist-message!
  "Persist a message to the project database"
  [project-id conversation-id message-data]
  (when-let [conn (connect-atm (keyword project-id))]
    (let [message-id (keyword (str "msg-" (System/currentTimeMillis) "-" (rand-int 1000)))
          tx-data [(merge {:db/id -1
                           :message/id message-id
                           :message/timestamp (util/now)}
                          message-data)]]
      (try
        (d/transact conn tx-data)
        (log! :info (str "Persisted message " message-id " for " project-id))
        message-id
        (catch Exception e
          (log! :error (str "Failed to persist message: " (.getMessage e)))
          nil)))))

(defn store-surrogate-exchange!
  "Store both question and answer in the database"
  [project-id conversation-id question answer]
  ;; Store the question (from system)
  (persist-message! project-id conversation-id
                    {:message/from :system
                     :message/type :question
                     :message/content question})

  ;; Store the answer (from surrogate)
  (persist-message! project-id conversation-id
                    {:message/from :surrogate
                     :message/type :answer
                     :message/content answer}))

;;; Response generation

(defn build-conversation-context
  "Build context string from conversation history"
  [session]
  (if (empty? (:conversation-history session))
    "This is the beginning of our conversation."
    (str "Previous conversation:\n"
         (str/join "\n"
                   (map #(str "Q: " (:question %)
                              "\nA: " (:answer %)
                              "\n")
                        (take-last 5 (:conversation-history session)))))))

(defn generate-expert-response
  "Generate a response from the surrogate expert"
  [project-id question]
  (if-let [session (get @expert-sessions-atom project-id)]
    (let [persona (:expert-persona session)
          context (build-conversation-context session)
          system-prompt (system-instruction (:domain persona) (:company-name persona))

          user-prompt (str context "\n\n"
                           "Current question: " question
                           "\n\nRemember to be specific and consistent with previous answers.")

          response (llm/query-llm [{:role "system" :content system-prompt}
                                   {:role "user" :content user-prompt}]
                                  :model-class :chat
                                  :llm-provider @sched-mcp.sutil/default-llm-provider)]

      ;; Update session history
      (update-session-history project-id question response)

      ;; Store in database if we have a conversation ID
      (when-let [conversation-id (:conversation-id session)]
        (store-surrogate-exchange! project-id conversation-id question response))

      ;; Return response with orange color indicator
      {:response response
       :expert-id (get-in session [:expert-persona :expert-id])
       :display-color "orange"})

    {:error "No expert session found for this project"}))

;;; MCP Tool interfaces

(defn start-surrogate-interview
  "Start an interview with a surrogate expert"
  [{:keys [domain company-name project-name]
    :or {project-name "Test Manufacturing"}}]
  (log! :info (str "Starting surrogate interview for " domain))

  ;; Create project with sur- prefix and force replace
  (let [project-id (str "sur-" (name domain))
        project-result (pdb/create-project! {:project-id project-id
                                                :project-name (or project-name
                                                                  (str "Surrogate " (name domain) " Interview"))
                                                :domain (name domain)
                                                :force-replace? true})

        ;; Create expert persona
        persona (create-expert-persona {:domain domain
                                        :company-name company-name})

        ;; Initialize session with conversation ID
        _session (init-expert-session (:project-id project-result) persona
                                      :conversation-id (:conversation-id project-result))]

    {:project-id (:project-id project-result)
     :conversation-id (name (:conversation-id project-result))
     :expert-id (:expert-id persona)
     :company-name (:company-name persona)
     :domain domain
     :message (str "Started surrogate interview for " (:company-name persona)
                   " (specializing in " (name domain) ")")
     :display-color "orange"}))

(defn surrogate-answer-question
  "Get an answer from the surrogate expert"
  [{:keys [project-id question]}]
  (generate-expert-response project-id question))

(defn get-surrogate-session
  "Get current session state for debugging/inspection"
  [project-id]
  (get @expert-sessions-atom project-id))

(defn list-surrogate-sessions
  "List all active surrogate sessions"
  []
  (keys @expert-sessions-atom))

(defn get-conversation-history
  "Retrieve conversation history from the database"
  [project-id]
  (when-let [conn (connect-atm (keyword project-id))]
    (let [messages (d/q '[:find ?from ?type ?content ?timestamp
                          :where
                          [?e :message/from ?from]
                          [?e :message/type ?type]
                          [?e :message/content ?content]
                          [?e :message/timestamp ?timestamp]]
                        @conn)]
      (->> messages
           (sort-by #(nth % 3)) ; Sort by timestamp
           (map (fn [[from type content timestamp]]
                  {:from from
                   :type type
                   :content content
                   :timestamp timestamp}))))))

;;; Convenience functions for REPL testing
(comment
  ;; Start a craft beer surrogate
  (def session (start-surrogate-interview {:domain :craft-beer
                                           :company-name "Mountain Peak Brewery"
                                           :project-name "Craft Beer Test"}))

  ;; Ask a question
  (surrogate-answer-question {:project-id (:project-id session)
                              :question "What are the main steps in your brewing process?"})

  ;; Check session state
  (get-surrogate-session (:project-id session))

  ;; List all sessions
  (list-surrogate-sessions))
