(ns sched-mcp.tools.surrogate.sur-util
  "Surrogate expert agent implementation for automated interview testing"
  (:require
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [datahike.api :as d]
   [sched-mcp.llm :as llm]
   [sched-mcp.sutil :as sutil :refer [connect-atm]]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :as util :refer [log!]]))

;;; ToDo:
;;;  - Eradicate the use of the current-expert-session. Integrate with the LangGraph
;;;  - Get the conversation history into the agent. I'll miss not having a real agent!

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
  [domain]
  (str
   (format "You are the production manager at a company that specializes in %s.\n" (name domain))
   "You are an expert in production and manage your company's supply chains.\n"
   "You help by answering an interviewer's questions that will allow us to collaborate in building a scheduling system for your company.\n\n"

   "Currently, you do not use any software in scheduling your work, except maybe spreadsheets and office software calendars.\n"
   "Your answers typically are short, just a few sentences each.\n"
   "Be specific and concrete in your answers, using actual numbers, times, and quantities when appropriate.\n"
   "If asked about challenges, mention realistic scheduling difficulties your company faces.\n"
   "Use the conversation history we provide to stay consistent with any facts, processes, or details you've already mentioned.\n\n"
   table-handling-instructions))

;;; Move to project_db if anyone else ever needs it.
(defn get-system-instruction
  "Return the system instruct text for a surrogate."
  [pid]
  (d/q '[:find ?sys-in . :where [_ :surrogate/system-instruction ?sys-in]]
       @(connect-atm pid)))

;;; This is called by the MCP tool, start_surrogate_expert.
(defn start-surrogate-interview
  "Start an interview with a surrogate expert. Creates a new project too."
  [{:keys [domain project-name] :or {project-name "Test Manufacturing"}}]
  (log! :info (str "Starting surrogate interview for " domain))
  (let [pid (as-> domain ?s ;; Create project with sur- prefix and force replace
              (name ?s) ; Convert keyword to string
              (str/trim ?s)
              (str/lower-case ?s)
              (str/replace ?s #"\s+" "-")
              (str "sur-" ?s)
              (keyword ?s))
        {:keys [pid]} (pdb/create-project-db! ; You don't always get the PID you asked for!
                       {:pid pid
                        :project-name (or project-name (str "Surrogate " (name domain) " Interview"))
                        :force-replace? true
                        :additional-info {:project/surrogate
                                          {:surrogate/system-instruction (system-instruction domain)}}})]
    (pdb/add-claim! pid (list 'surrogate pid))
    {:pid pid
     :domain domain
     :message (str "Started surrogate interview for project " pid " specializing in " domain ".")
     :display-color "orange"}))

(defn table-xml2clj
  "Remove some useless aspects of the parsed XHTML, including string content and attrs."
  [table-xml]
  (letfn [(x2c [x]
            (cond (seq? x) (mapv x2c x)
                  (map? x) (reduce-kv (fn [m k v]
                                        (cond (= k :content) (assoc m k (->> v (remove #(and (string? %) (re-matches #"^\s+" %))) vec x2c))
                                              (= k :attrs) m ; There aren't any values here in our application.
                                              :else (assoc m k (x2c v))))
                                      {}
                                      x)
                  (vector? x) (mapv x2c x)
                  :else x))]
    (x2c table-xml)))

(defn ru-text-to-var
  "Stub for the real thing."
  [s]
  (assert (string? s))
  (-> s
      str/lower-case
      (str/replace #"\s+" "-")))

(defn table2obj
  "Convert the ugly XML-like object (has :tr :th :td) to an object with :table-headings and :table-data, where
  (1) :table-headings is a vector of maps with :title and :key and,
  (2) :table-data is a vector of maps with keys that are the :key values of (1)
  These :key values are :title as a keyword. For example:

   ugly-table:

  {:tag :table,
   :content [{:tag :tr, :content [{:tag :th, :content ['Dessert (100g serving)']} {:tag :th, :content ['Carbs (g)']} {:tag :th, :content ['Protein (g)']}]}
             {:tag :tr, :content [{:tag :td, :content ['frozen yogurt']} {:tag :td, :content [24]}  {:tag :td, :content [4.0]}]}
             {:tag :tr, :content [{:tag :td, :content ['ice cream']}     {:tag :td, :content [37]}  {:tag :td, :content [4.3]}]}]}

  Would result in:
  {:table-headings [{:title 'Dessert (100g serving)', :key :dessert}
                    {:title 'Carbs (g)',              :key :carbs}
                    {:title 'Protein (g)',            :key :protein}]
   :table-data [{:dessert 'frozen yogurt', :carbs 24 :protein 4.0}
                {:dessert 'ice cream',     :carbs 37 :protein 4.3}]}."
  [ugly-table]
  (let [heading (-> ugly-table :content first :content)
        titles (if (every? #(= (:tag %) :th) heading)
                 (->> heading
                      (mapv #(-> % :content first))
                      (mapv #(-> {}
                                 (assoc :title %)
                                 (assoc :key (-> % ru-text-to-var keyword))))) ; <===== ToDo Get ru/text-to-var agent. (ru/text-to-var)
                 (log! :warn "No titles in table2obj."))
        title-keys (map :key titles)
        data-rows (->> ugly-table
                       :content ; table content
                       rest ; everything but header
                       (mapv (fn [row]
                               (mapv #(or (-> % :content first) "") (:content row)))))]
    {:table-headings titles
     :table-body (mapv (fn [row] (zipmap title-keys row)) data-rows)}))

;;; ToDo: Should put a marker in the :text to indicate where the table was found ???
;;; ToDo: What this code should be doing is still up in the air!
(defn separate-table-aux
  "The argument is a text string which may contain a single block of HTML (a table, it turns out) between the markers '#+begin_src HTML ... #+end_src'.
   (In this function we don't care what is between the markers, but in usage it is typically a single HTML table.)
   Return a map where
      :content is the argument text,
      :text is the argument string minus the content between the marker, and
      :table-html is the substring between the markers. If no markers were found an empty string will be returned."
  [text]
  (let [in-table? (atom false)
        first-table-line? (atom true)
        result (loop [lines (str/split-lines text)
                      res {:text "" :table-html "" :content text}]
                 (let [l (first lines)]
                   (if (empty? lines)
                     res
                     (recur (rest lines)
                            (cond
                              ;; Table begin marker - skip this line and enter table mode
                              (and l (not @in-table?) (re-matches #"(?i)^\s*\#\+begin_src\s+HTML\s*" l))
                              (do (reset! in-table? true)
                                  (reset! first-table-line? true)
                                  res)

                              ;; End marker for table - skip this line, exit table mode
                              (and l @in-table? (re-matches #"(?i)^\s*\#\+end_src\s*" l))
                              (do (reset! in-table? false)
                                  ;; Add trailing newline to table-html
                                  (-> res
                                      (update :table-html #(str % "\n"))
                                      (update :text #(if (empty? %) "" (str % "\n")))))

                              ;; Regular text outside table
                              (and l (not @in-table?))
                              (update res :text #(if (empty? %) l (str % "\n" l)))

                              ;; Content inside table - add leading newline for first line
                              (and l @in-table?)
                              (if @first-table-line?
                                (do (reset! first-table-line? false)
                                    (update res :table-html #(str % "\n" l)))
                                (update res :table-html #(str % "\n" l)))

                              ;; Empty line case
                              :else res)))))]
    ;; Check if we found table markers but no actual content (just whitespace/newlines)
    (cond-> (update result :table-html str/trim)
      (-> result :table-html empty?) (dissoc :table-html))))

(defn separate-table
  "If argument containing a table, returns a map containing
      :full-text,  a string which is all the text of the message, inclusive of tables etc.
      :table (a map providing what the client needs to present the table.
   Otherwise returns nil"
  [text]
  (let [{:keys [table-html]} (separate-table-aux text)]
    (when (not-empty table-html)
      (try
        ;; Clean up HTML entities for XML parsing
        (let [clean-html (-> table-html
                             (str/replace "&" "&amp;") ; Fix unescaped ampersands
                             (str/replace "&amp;amp;" "&amp;") ; Avoid double-escaping
                             (str/replace "&amp;lt;" "&lt;")
                             (str/replace "&amp;gt;" "&gt;"))
              parsed-table (-> clean-html java.io.StringReader. xml/parse table-xml2clj table2obj)]
          {:full-text text :table parsed-table})
        (catch Throwable e
          (log! :error (str "Error processing table. HTML = " table-html (.getMessage e))))))))

;;; This gets called by interview_nodes.clj
(defn surrogate-answer-question
  "Get an answer from the surrogate expert. Returns a map containing keyword :response."
  [pid question]
  ;; Ensure project-id is a keyword for comparison with session
  (log! :info (str "Surrogate asked: " question))
  (let [cid (pdb/get-active-cid pid)
        context "Responding to question."
        system-prompt (get-system-instruction pid)
        user-prompt (str context "\n\n"
                         "Current question: " question
                         "\n\nRemember to be specific and consistent with previous answers.")

        response (llm/query-llm [{:role "system" :content system-prompt}
                                 {:role "user" :content user-prompt}]
                                :model-class :chat ; <========================================== Investigate!
                                :llm-provider @sutil/default-llm-provider)
        q-table (separate-table question)
        a-table (separate-table response)]
    ;; ToDo: Does the response answer the question? LangGraph stuff should know???
    (let [q-msg-id (pdb/add-msg! {:pid pid :cid cid :content question :table q-table})]
      (pdb/add-msg! {:pid pid :cid cid :content response :table a-table :answers-question q-msg-id}))
    (log! :info (str "Surrogate responds: " response))
    {:response response}))
