(ns sched-mcp.surrogate
  "Surrogate expert agent implementation for automated interview testing"
  (:require
   [clojure.string :as str]
   [datahike.api :as d]
   [sched-mcp.llm :as llm]
   [sched-mcp.sutil :as sutil :refer [connect-atm]]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :as util :refer [log!]]))

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

;;;   "Single active expert session"
(defonce current-expert-session
  (atom nil))

(defn init-expert-session
  "Initialize a new expert session"
  [project-id expert-persona & {:keys [conversation-id]}]
  (let [session {:expert-persona expert-persona
                 :conversation-history []
                 :session-start (util/now)
                 :conversation-id conversation-id
                 :project-id project-id}]
    (reset! current-expert-session session)
    session))

(defn update-session-history
  "Add a Q&A pair to the session history"
  [project-id question answer]
  (when (and @current-expert-session
             (= (:project-id @current-expert-session) project-id))
    (swap! current-expert-session update :conversation-history
           conj {:question question
                 :answer answer
                 :timestamp (util/now)})))

;;; Response generation

(defn generate-expert-response
  "Generate a response from the surrogate expert"
  [project-id question]
  (if-let [session @current-expert-session]
    (when (= (:project-id session) project-id)
      (let [persona (:expert-persona session)
            context "Responding to question."
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

        ;; Message persistence should be handled by the interviewer tools
        ;; (The interviewer will store both question and answer)

        ;; Return response with orange color indicator
        {:response response
         :expert-id (get-in session [:expert-persona :expert-id])
         :display-color "orange"}))
    {:error (str "No active expert session for project " project-id)}))

;;; MCP Tool interfaces

(defn start-surrogate-interview
  "Start an interview with a surrogate expert"
  [{:keys [domain company-name project-name]
    :or {project-name "Test Manufacturing"}}]
  (log! :info (str "Starting surrogate interview for " domain))

  ;; Create project with sur- prefix and force replace
  (let [pid (as-> domain ?s
              (name ?s) ; Convert keyword to string
              (str/trim ?s)
              (str/lower-case ?s)
              (str/replace ?s #"\s+" "-")
              (str "sur-" ?s)
              (keyword ?s))
        project-result (pdb/create-project-db! {:pid pid
                                                :project-name (or project-name
                                                                  (str "Surrogate " (name domain) " Interview"))
                                                :force-replace? true})

        ;; Create expert persona
        persona (create-expert-persona {:domain domain
                                        :company-name company-name})

        ;; Initialize session with conversation ID
        _session (init-expert-session (:pid project-result) persona
                                      :conversation-id (:cid project-result))]

;; Add claim that this is a surrogate project
    (pdb/add-claim! (:pid project-result)
                    (list 'surrogate (:pid project-result))
                    {:conversation-id :process})

    {:project-id (:pid project-result)
     :conversation-id (name (:cid project-result))
     :expert-id (:expert-id persona)
     :company-name (:company-name persona)
     :domain domain
     :message (str "Started surrogate interview for " (:company-name persona)
                   " (specializing in " (name domain) ")")
     :display-color "orange"}))

(defn surrogate-answer-question
  "Get an answer from the surrogate expert"
  [{:keys [project-id question]}]
  ;; Ensure project-id is a keyword for comparison with session
  (generate-expert-response (keyword project-id) question))

(defn get-surrogate-session
  "Get the current session if it matches the project-id"
  [project-id]
  (when-let [session @current-expert-session]
    ;; Ensure project-id is a keyword for comparison
    (when (= (:project-id session) (keyword project-id))
      session)))

(defn get-conversation-history
  "Retrieve conversation history from the database"
  [project-id]
  (when-let [conn (connect-atm (keyword project-id))]
    (let [messages (d/q '[:find ?from ?type ?content ?timestamp
                          :where
                          [?e :message/from ?from]
                          [?e :message/type ?type]
                          [?e :message/content ?content]
                          [?e :message/time ?timestamp]]
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
