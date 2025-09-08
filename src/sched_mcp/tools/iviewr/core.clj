(ns sched-mcp.tools.iviewr.core
  "Core interviewer tools for Discovery Schema based interviews
   These tools use LLMs to formulate questions and interpret responses"
  (:require
   [clojure.edn :as edn]
   [sched-mcp.llm :as llm]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tools.orch.ds-util :as dsu]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.util :refer [log!]]))

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

(defn create-get-interview-progress-tool
  "Creates the tool for getting overall interview progress"
  [system-atom]
  {:tool-type :get-interview-progress
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
        ds (sdb/get-discovery-schema-JSON ds-id)
        ;; Get current ASCR
        {:ascr/keys [dstruct budget-left] ascr-str :ascr/str} (pdb/get-ASCR pid ds-id)
        ascr (when (or ascr-str dstruct) (edn/read-string (or ascr-str dstruct)))]

    (if-not ds
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Initialize LLM if needed
        (when-not (seq @llm/agent-prompts)
          (llm/init-llm!))

        ;; Generate question using LLM
        (let [prompt (llm/ds-question-prompt
                      {:ds ds
                       :ascr ascr
                       :budget-remaining budget-left})
              result (llm/complete-json prompt :model-class :chat)
              question-text (:question result)
              ;; Store the question as a message
              question-mid (pdb/add-msg! {:pid pid
                                          :cid cid
                                          :from :system
                                          :content question-text
                                          :pursuing-DS ds-id
                                          :question-type :generated-question})]
          (log! :info (str "Generated question for " ds-id " in " conversation-id " with mid " question-mid))
          {:question {:id question-mid ; Use the actual message ID
                      :text question-text
                      :ds_id (name ds-id)
                      :help (or (:help result)
                                "Provide detailed information to help complete the schema")}
           :context {:ds_objective (get-in ds [:DS :interview-objective])
                     :fields_remaining (if ascr
                                         (- (count (keys (get-in ds [:DS :EADS])))
                                            (count (keys ascr)))
                                         (count (keys (get-in ds [:DS :EADS]))))
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
                                            :pursuing-DS ds-id
                                            :question-type :fallback-question})]
            {:question {:id fallback-mid
                        :text fallback-text
                        :ds_id (name ds-id)
                        :help "Describe the steps involved in your production process"}
             :context {:ds_objective (get-in ds [:DS :interview-objective])
                       :fields_remaining (count (get-in ds [:DS :EADS]))
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
        ds (sdb/get-discovery-schema-JSON ds-id)]
    (if-not ds
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Initialize LLM if needed
        (when-not (seq @llm/agent-prompts)
          (llm/init-llm!))

        ;; Use LLM to interpret the answer
        (let [prompt (llm/ds-interpret-prompt {:ds ds :question question-asked :answer answer})
              result (llm/complete-json prompt :model-class :extract)
              scr (:scr result)
              ;; Add message with the answer
              answer-mid (pdb/add-msg! {:pid pid
                                        :cid cid
                                        :from :user
                                        :content answer
                                        :pursuing-DS ds-id})
              ;; Link answer to question if question-id provided
              _ (when question-id
                  (pdb/update-msg! pid cid answer-mid
                                   {:message/answers-question question-id}))
              ;; Store the SCR with the message
              _ (when scr
                  (pdb/update-msg! pid cid answer-mid
                                   {:message/SCR (str scr)}))
              ;; Initialize ASCR if needed
              _ (when-not (pdb/ASCR-exists? pid ds-id)
                  (pdb/init-ASCR! pid ds-id))
              ;; Update ASCR with new SCR
              updated-ascr (dsu/combine-ds! ds-id pid)
              _ (pdb/put-ASCR! pid ds-id updated-ascr)
              ;; Reduce budget
              _ (pdb/reduce-questioning-budget! pid ds-id)
              ;; Check if DS is complete
              complete? (dsu/ds-complete? ds-id pid)]

          ;; Only mark complete if actually complete
          (when complete?
            (pdb/mark-ASCR-complete! pid ds-id))

          ;; Return comprehensive response
          {:scr (merge {:answered-at (java.util.Date.)
                        :question question-asked
                        :raw-answer answer}
                       scr)
           :message_id answer-mid
           :updated_ascr updated-ascr
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
                        (count (keys (:eads ds)))))}
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
    (if-not ds-id
      {:error "No active DS pursuit found"}
      {:ds_id (name ds-id)
       :ds_template (:ds dstruct)
       :interview_objective (:interview-objective dstruct)
       :current_ascr ascr
       :pursuit_status (if completed? :completed :incomplete)})))

;;; Get Interview Progress Tool

(defmethod tool-system/tool-name :get-interview-progress [_]
  "sys_get_interview_progress")

(defmethod tool-system/tool-description :get-interview-progress [_]
  "Returns overall interview progress including completed Discovery Schemas and current phase.")

(defmethod tool-system/tool-schema :get-interview-progress [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema}
   :required ["project_id"]})

(defmethod tool-system/validate-inputs :get-interview-progress [_ inputs]
  (tool-system/validate-required-params inputs [:project-id]))

(defmethod tool-system/execute-tool :get-interview-progress
  [{:keys [_system-atom]} {:keys [project-id]}]
  (try
    (let [progress {:status :not-yet-implemented}] ; (orch/get-interview-progress (keyword project-id))]
      progress)
    (catch Exception e
      (log! :info (str "Error in get-interview-progress: " (.getMessage e)))
      {:error (.getMessage e)})))

;;; Helper to create all interviewer tools

(defn create-interviewer-tools
  "Create all interviewer tools with shared system atom"
  [system-atom]
  [(create-formulate-question-tool system-atom)
   (create-interpret-response-tool system-atom)
   (create-get-current-ds-tool system-atom)
   (create-get-interview-progress-tool system-atom)])
