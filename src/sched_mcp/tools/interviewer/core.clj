(ns sched-mcp.tools.interviewer.core
  "Core interviewer tools for Discovery Schema based interviews
   These tools use LLMs to formulate questions and interpret responses"
  (:require
   [clojure.string :as str]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.ds-loader :as ds]
   [sched-mcp.ds-combine :as combine]
   [sched-mcp.llm-direct :as llm] ; Using direct HTTP implementation
   [sched-mcp.util :refer [alog!]]
   [datahike.api :as d]
   [sched-mcp.sutil :refer [connect-atm]]))

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
  "formulate_question")

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
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id]}]
  (let [;; Load the DS
        ds (ds/get-cached-ds (keyword ds-id))
        ;; Get current ASCR
        ascr (combine/get-ascr (keyword project-id) (keyword ds-id))
        ;; Get budget info
        conn (connect-atm (keyword project-id))
        budget-info (d/q '[:find [?allocated ?used]
                           :in $ ?ds-id
                           :where
                           [?p :pursuit/ds-id ?ds-id]
                           [?p :pursuit/status :active]
                           [?p :pursuit/budget-allocated ?allocated]
                           [?p :pursuit/budget-used ?used]]
                         @conn (keyword ds-id))
        [allocated used] (or budget-info [10 0])
        budget-remaining (- allocated used)]

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
                       :budget-remaining budget-remaining})
              result (llm/complete-json prompt :model-class :chat)
              question-id (keyword (str "q-" (System/currentTimeMillis)))]
          (alog! (str "Generated question for " ds-id " in " conversation-id))
          {:question {:id (name question-id)
                      :text (:question result)
                      :ds_id ds-id
                      :help (or (:help result)
                                "Provide detailed information to help complete the schema")}
           :context {:ds_objective (:interview-objective ds)
                     :fields_remaining (- (count (keys (:eads ds)))
                                          (count (keys ascr)))
                     :ascr_summary (if (empty? ascr)
                                     "No data collected yet"
                                     (str (count ascr) " fields filled"))
                     :budget_remaining budget-remaining
                     :rationale (:rationale result)
                     :targets (:targets result)}})
        (catch Exception e
          (alog! (str "LLM error in formulate-question: " (.getMessage e)) {:level :error})
          ;; Fallback to simple question
          {:question {:id (str "q-" (System/currentTimeMillis))
                      :text "Can you tell me more about your process?"
                      :ds_id ds-id
                      :help "Describe the steps involved in your production process"}
           :context {:ds_objective (:interview-objective ds)
                     :fields_remaining (count (:eads ds))
                     :error_fallback true}})))))

;;; Interpret Response Tool

(defmethod tool-system/tool-name :interpret-response [_]
  "interpret_response")

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
                                 :description "The question that was asked"}}
   :required ["project_id" "conversation_id" "ds_id" "answer" "question_asked"]})

(defmethod tool-system/validate-inputs :interpret-response [_ inputs]
  (tool-system/validate-required-params inputs
                                        [:project-id :conversation-id :ds-id
                                         :answer :question-asked]))

(defmethod tool-system/execute-tool :interpret-response
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id answer question-asked]}]
  (let [ds (ds/get-cached-ds (keyword ds-id))]
    (if-not ds
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Initialize LLM if needed
        (when-not (seq @llm/agent-prompts)
          (llm/init-llm!))

        ;; Use LLM to interpret the answer
        (let [prompt (llm/ds-interpret-prompt
                      {:ds ds
                       :question question-asked
                       :answer answer})
              result (llm/complete-json prompt :model-class :extract)
              ;; Store SCR in message
              scr (:scr result)]
          (alog! (str "Interpreted response for " ds-id " in " conversation-id))
          {:scr (merge {:answered-at (java.util.Date.)
                        :question question-asked
                        :raw-answer answer}
                       scr)
           :confidence (or (:confidence result) 0.8)
           :ambiguities (or (:ambiguities result) [])
           :follow_up (:follow_up result)
           :commit-notes (str "Extracted: " (keys scr))})
        (catch Exception e
          (alog! (str "LLM error in interpret-response: " (.getMessage e)) {:level :error})
          ;; Fallback SCR
          {:scr {:answered-at (java.util.Date.)
                 :question question-asked
                 :raw-answer answer
                 :extraction-failed true}
           :confidence 0.0
           :ambiguities ["Could not extract structured data"]
           :commit-notes "LLM extraction failed - storing raw answer only"})))))

;;; Get Current DS Tool

(defmethod tool-system/tool-name :get-current-ds [_]
  "get_current_ds")

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
  (let [conn (connect-atm (keyword project-id))
        ;; Find active pursuit
        pursuit (d/q '[:find ?pursuit ?ds-id
                       :in $ ?cid
                       :where
                       [?c :conversation/id ?cid]
                       [?c :conversation/active-pursuit ?pursuit]
                       [?pursuit :pursuit/ds-id ?ds-id]]
                     @conn (keyword conversation-id))]
    (if-not pursuit
      {:error "No active DS pursuit found"}
      (let [[pursuit-eid ds-id] (first pursuit)
            ds (ds/get-cached-ds ds-id)
            ascr (combine/get-ascr (keyword project-id) ds-id)]
        {:ds_id (str (namespace ds-id) "/" (name ds-id))
         :ds_template (:eads ds)
         :interview_objective (:interview-objective ds)
         :current_ascr ascr
         :pursuit_status (d/q '[:find ?status .
                                :in $ ?p
                                :where [?p :pursuit/status ?status]]
                              @conn pursuit-eid)}))))

;;; Helper to create all interviewer tools

(defn create-interviewer-tools
  "Create all interviewer tools with shared system atom"
  [system-atom]
  [(create-formulate-question-tool system-atom)
   (create-interpret-response-tool system-atom)
   (create-get-current-ds-tool system-atom)])
