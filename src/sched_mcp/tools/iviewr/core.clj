(ns sched-mcp.tools.iviewr.core
  "Core interviewer tools for Discovery Schema based interviews
   These tools use LLMs to formulate questions and interpret responses"
  (:require
   [clojure.string :as str]
   [clojure.data.json :as json]
   [sched-mcp.llm :as llm]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tools.orch.ds-util :as dsu]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.util :refer [alog! log!]]))

;;; Tool configurations
(defn create-formulate-question-tool
  "Creates the tool for formulating interview questions"
  [system-atom]
  {:tool-type :formulate-question
   :system-atom system-atom})

(defn create-interpret-response-tool
  "Creates the tool for interpreting responses into SCRs"
  [system-atom]
  {:tool-type :interpret-response
   :system-atom system-atom})

(defn create-get-current-ds-tool
  "Creates the tool for getting current DS and ASCR"
  [system-atom]
  {:tool-type :get-current-ds
   :system-atom system-atom})

;;; Formulate Question Tool

(defmethod tool-system/tool-name :formulate-question [_]
  "iviewr_formulate_question")

(defmethod tool-system/tool-description :formulate-question [_]
  "Generate contextually appropriate interview questions based on Discovery Schema and current ASCR. This tool uses LLM reasoning to create natural questions that gather required information.")

(defmethod tool-system/tool-schema :formulate-question [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :ds_id tool-system/ds-id-schema}
   :required ["project_id" "conversation_id" "ds_id"]})

(defmethod tool-system/validate-inputs :formulate-question [_ inputs]
  (tool-system/validate-required-params inputs [:project-id :conversation-id :ds-id]))

(defmethod tool-system/execute-tool :formulate-question
  [_ {:keys [project-id conversation-id ds-id]}]
  (let [pid (keyword project-id)
        cid (keyword conversation-id)
        ds-id (keyword ds-id)
        ds-json (sdb/get-discovery-schema-JSON ds-id)
        ds-full (sdb/get-DS-instructions ds-id)
        ;; Get current ASCR
        {:ascr/keys [dstruct budget-left]} (pdb/get-ASCR pid ds-id)
        ascr (or dstruct {})] ; dstruct is already a map, not a string
    (alog! (str "iviewr_formulate_question " pid " " cid " " ds-id))

    (if (= ds-full "") ; get-DS-instructions returns empty string when not found
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Initialize LLM if needed
        (when-not (seq @llm/agent-prompts)
          (llm/init-llm!))

        ;; Build conversation history in the format expected by the prompt
        (let [conversation (pdb/get-conversation pid cid)
              message-history (mapv (fn [msg]
                                      (cond
                                        (= (:message/from msg) :system)
                                        {:interviewer (:message/content msg)}
                                        (#{:human :surrogate} (:message/from msg))
                                        {:expert (:message/content msg)}
                                        :else nil))
                                    (:conversation/messages conversation))
              message-history (remove nil? message-history)

              ;; Parse the DS JSON to get the proper structure
              ds-obj (json/read-str ds-json :key-fn keyword)

              ;; Generate question using the base-iviewr-instructions format
              prompt (llm/ds-question-prompt
                      {:ds ds-obj ; Pass parsed DS object
                       :ascr ascr
                       :message-history message-history
                       :budget-remaining budget-left
                       :interview-objective (-> (:interview-objective ds-full)
                                                (clojure.string/replace #"But remember.*\{\"message-type\".*\"OK\"\}\." "")
                                                (clojure.string/replace #"The correct response.*\"OK\"\}\." "")
                                                str/trim)})
              result (llm/complete-json prompt :model-class :chat)
              ;; Extract question from the expected format
              question-text (get result :question-to-ask (:question result))
              ;; Store the question as a message
              question-mid (pdb/add-msg! {:pid pid
                                          :cid cid
                                          :from :system
                                          :content question-text
                                          :pursuing-DS ds-id})]
          (log! :info (str "Generated question for " ds-id " in " conversation-id " with mid " question-mid))
          {:question {:id question-mid ; Use the actual message ID
                      :text question-text
                      :ds_id (name ds-id)
                      :help (or (:help result)
                                "Provide detailed information to help complete the schema")}
           :context {:ds_objective (:interview-objective ds-full) ; Use Clojure data structure
                     :fields_remaining (if ascr
                                         (let [ds-fields (dissoc (:DS ds-full) :DS-id)]
                                           (- (count (keys ds-fields))
                                              (count (keys ascr))))
                                         (let [ds-fields (dissoc (:DS ds-full) :DS-id)]
                                           (count (keys ds-fields))))
                     :ascr_summary (if (empty? ascr)
                                     "No data collected yet"
                                     (str (count ascr) " fields filled"))
                     :budget_remaining budget-left
                     :rationale (:rationale result)
                     :targets (:targets result)}})
        (catch Exception e
          (log! :error (str "LLM error in formulate-question: " (.getMessage e)))
          ;; Fallback to simple question
          (let [fallback-text "Can you tell me more about your process?"
                fallback-mid (pdb/add-msg! {:pid pid
                                            :cid cid
                                            :from :system
                                            :content fallback-text
                                            :pursuing-DS ds-id})]
            {:question {:id fallback-mid
                        :text fallback-text
                        :ds_id (name ds-id)
                        :help "Describe the steps involved in your production process"}
             :context {:ds_objective (:interview-objective ds-full)
                       :fields_remaining (let [ds-fields (dissoc (:DS ds-full) :DS-id)]
                                           (count (keys ds-fields)))
                       :error_fallback true}}))))))

;;; Interpret Response Tool

(defmethod tool-system/tool-name :interpret-response [_]
  "iviewr_interpret_response")

(defmethod tool-system/tool-description :interpret-response [_]
  "Interpret a natural language answer into a Schema-Conforming Response (SCR). This tool uses LLM reasoning to extract structured data from conversational responses.")

(defmethod tool-system/tool-schema :interpret-response [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :ds_id tool-system/ds-id-schema
                :answer {:type "string"
                         :description "The user's natural language answer"}
                :question_asked {:type "string"
                                 :description "The question that was asked"}
                :question_id {:type "string"
                              :description "The message ID of the question being answered (optional)"}}
   :required ["project_id" "conversation_id" "ds_id" "answer" "question_asked"]})

(defmethod tool-system/validate-inputs :interpret-response [_ inputs]
  (tool-system/validate-required-params inputs
                                        [:project-id :conversation-id :ds-id
                                         :answer :question-asked]))

(defmethod tool-system/execute-tool :interpret-response
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id answer question-asked question-id]}]
  (let [pid (keyword project-id)
        cid (keyword conversation-id)
        ds-id (keyword ds-id)
        ds-json (sdb/get-discovery-schema-JSON ds-id)
        ds-full (sdb/get-DS-instructions ds-id)]

    (alog! (str "iviewr_interpret_response " pid " " cid " " ds-id))
    (if-not ds-json
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Initialize LLM if needed
        (when-not (seq @llm/agent-prompts)
          (llm/init-llm!))

        ;; Get current ASCR
        (let [{:ascr/keys [dstruct budget-left]} (pdb/get-ASCR pid ds-id)
              ascr (or dstruct {})

              ;; Add the answer message first
              answer-mid (pdb/add-msg! {:pid pid
                                        :cid cid
                                        :from :human
                                        :content answer
                                        :pursuing-DS ds-id})

              ;; Build conversation history including the new answer
              conversation (pdb/get-conversation pid cid)
              message-history (mapv (fn [msg]
                                      (cond
                                        (= (:message/from msg) :system)
                                        {:interviewer (:message/content msg)}
                                        (#{:human :surrogate} (:message/from msg))
                                        {:expert (:message/content msg)}
                                        :else nil))
                                    (:conversation/messages conversation))
              message-history (vec (remove nil? message-history))

              ;; Parse the DS JSON
              ds-obj (json/read-str ds-json :key-fn keyword)

              ;; Use LLM to interpret the answer using base-iviewr-instructions format
              prompt (llm/ds-interpret-prompt {:ds ds-obj
                                               :question question-asked
                                               :answer answer
                                               :message-history message-history
                                               :ascr ascr
                                               :budget-remaining budget-left
                                               :interview-objective (-> (:interview-objective ds-full)
                                                                        (clojure.string/replace #"But remember.*\{\"message-type\".*\"OK\"\}\." "")
                                                                        (clojure.string/replace #"The correct response.*\"OK\"\}\." "")
                                                                        str/trim)})
              result (llm/complete-json prompt :model-class :extract)
              ;; The SCR should be the entire result per the prompt instructions
              ;; Remove any ASCR metadata that might have been included
              scr (dissoc result :iviewr-failure :ascr/budget-left :ascr/id :ascr/dstruct)

              ;; Check for failure response
              _ (when (:iviewr-failure result)
                  (throw (ex-info "Interviewer reported failure" {:reason (:iviewr-failure result)})))

              ;; Link answer to question if question-id provided
              _ (when question-id
                  (pdb/update-msg! pid cid answer-mid
                                   {:message/answers-question (if (string? question-id)
                                                                (Long/parseLong question-id)
                                                                question-id)}))
              ;; Store the SCR with the message
              _ (when scr
                  (pdb/put-msg-SCR! pid cid scr))
              ;; Initialize ASCR if needed
              _ (when-not (pdb/ASCR-exists? pid ds-id)
                  (pdb/init-ASCR! pid ds-id))
              ;; Update ASCR with new SCR using the pure function
              current-ascr-data (pdb/get-ASCR pid ds-id)
              current-ascr (or (:ascr/dstruct current-ascr-data) {})
              updated-ascr (dsu/ds-combine ds-id scr current-ascr)
              _ (pdb/put-ASCR! pid ds-id updated-ascr)
              ;; Reduce budget
              _ (pdb/reduce-questioning-budget! pid ds-id)
              ;; Check if DS is complete using the pure function
              complete? (dsu/ds-complete? ds-id updated-ascr)]

          ;; Only mark complete if actually complete
          (when complete?
            (pdb/mark-ASCR-complete! pid ds-id))

          ;; Return comprehensive response
          {:scr (merge {:answered-at (java.util.Date.)
                        :question question-asked
                        :raw-answer answer
                        :DS scr}
                       scr)
           :message_id answer-mid
           :ascr_updated true
           :ds_complete complete?
           :budget_remaining (pdb/get-questioning-budget-left! pid ds-id)})

        (catch Exception e
          (log! :error (str "Error in interpret-response: " (.getMessage e)))
          {:error (str "Failed to interpret response: " (.getMessage e))})))))

#_(try
     ;; Update pursuit status if complete
    (when complete? ; <=================================
      (d/transact conn [{:db/id pursuit-eid
                         :pursuit/status :complete
                         :pursuit/completed-at (java.util.Date.)}])
      (log! :info (str "DS " ds-id " marked complete")))

     ;; Return comprehensive result ; <================================= Whole completeness thing!
    {:scr (merge {:answered-at (java.util.Date.)
                  :question question-asked
                  :raw-answer answer}
                 SCR)
     :confidence (or (:confidence result) 0.8)
     :ambiguities (or (:ambiguities result) [])
     :follow_up (:follow_up result)
     :commit-notes (str "Extracted: " (keys SCR))
     :updated_ascr updated-ascr
     :ds_complete complete?
     :completeness (if complete?
                     1.0
                     (/ (count (keys updated-ascr))
                        (count (keys (:DS ds)))))}
    (catch Exception e
      (log! :error (str "Error in interpret-response: " (.getMessage e)))
       ;; Still try to store raw answer
      (try
        (let [conn (connect-atm pid)
              message-id (keyword (str "msg-" (System/currentTimeMillis)))
              message-data {:message/id message-id
                            :message/conversation cid
                            :message/type :answer
                            :message/from :user

                            :message/content answer
                            :message/timestamp (java.util.Date.)}]
          (d/transact conn [message-data]))
        (catch Exception e2
          (log! :error (str "Failed to store fallback message: " (.getMessage e2)))))
       ;; Return error response
      {:scr {:answered-at (java.util.Date.)
             :question question-asked
             :raw-answer answer
             :extraction-failed true}
       :confidence 0.0
       :ambiguities ["Could not extract structured data"]
       :commit-notes "LLM extraction failed - storing raw answer only"}))

;;; Get Current DS Tool

(defmethod tool-system/tool-name :get-current-ds [_]
  "sys_get_current_ds")

(defmethod tool-system/tool-description :get-current-ds [_]
  "Get the current Discovery Schema template and ASCR (Aggregated Schema-Conforming Response) for an active DS pursuit.")

(defmethod tool-system/tool-schema :get-current-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/validate-inputs :get-current-ds [_ inputs]
  (tool-system/validate-required-params inputs [:project-id :conversation-id]))

(defmethod tool-system/execute-tool :get-current-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id]}]
  (let [pid (keyword project-id)
        cid (keyword conversation-id)
        ds-id (pdb/get-current-DS pid cid)
        {:ascr/keys [completed? dstruct] :as ascr} (pdb/get-ASCR pid ds-id)]
    (alog! (str "sys_get_current_ds " pid " " cid " " ds-id))
    (if-not ds-id
      {:error "No active DS pursuit found"}
      {:ds_id (name ds-id)
       :ds_template (:ds dstruct)
       :interview_objective (:interview-objective dstruct)
       :current_ascr ascr
       :pursuit_status (if completed? :completed :incomplete)})))

;;; Helper to create all interviewer tools

(defn create-interviewer-tools
  "Create all interviewer tools with shared system atom"
  [system-atom]
  [(create-formulate-question-tool system-atom)
   (create-interpret-response-tool system-atom)
   (create-get-current-ds-tool system-atom)])
