(ns sched-mcp.tools.iviewr.core
  "Core interviewer tools for Discovery Schema based interviews
   These tools use LLMs to formulate questions and interpret responses"
  (:require
   [clojure.data.json :as json]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.interviewing.interview-graph :as igraph]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.util :refer [alog! log!]]))

(defmethod tool-system/tool-name :conduct-interview [_]
  "conduct_interview")

(defmethod tool-system/tool-description :conduct-interview [_]
  "Conduct a complete autonomous interview for a Discovery Schema using LangGraph.
The interviewer will formulate questions, interact with the surrogate/expert, interpret responses,
and build up the ASCR until the DS is complete or budget is exhausted. Returns the completed ASCR.")

(defmethod tool-system/tool-schema :conduct-interview [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :ds_id tool-system/ds-id-schema
                :budget {:type "number"
                         :description "Question budget for this interview (default: 10.0)"}}
   :required ["project_id" "conversation_id" "ds_id"]})

(defmethod tool-system/validate-inputs :conduct-interview [_ inputs]
  (tool-system/validate-required-params inputs [:project_id :conversation_id :ds_id]))

(defmethod tool-system/execute-tool :conduct-interview
  [_tag {:keys [project_id conversation_id ds_id budget]}]
  (let [pid (keyword project_id)
        cid (keyword conversation_id)
        ds-id (keyword ds_id)
        budget (or budget 1.0)]

    (alog! (str "conduct_interview " pid " " cid " " ds-id " budget=" budget))

    (try
      ;; Create initial interview state
      (let [initial-state (istate/make-interview-state
                           {:ds-id ds-id
                            :pid pid
                            :cid cid
                            :budget-left budget})
            final-state (igraph/run-interview initial-state)
            {:keys [ascr messages complete? budget-left]} final-state]

        ;; Store ASCR in project DB
        (when-not (pdb/ASCR-exists? pid ds-id)
          (pdb/init-ASCR! pid ds-id))
        (pdb/put-ASCR! pid ds-id ascr)
        (when complete?
          (pdb/mark-ASCR-complete! pid ds-id))
        ;; Store messages in project DB
        (log! :info (str "Completed autonomous interview for " ds-id
                         ". Complete: " complete?
                         ", Messages: " (count messages)
                         ", Budget remaining: " budget-left))

        {:status "success"
         :ds_id (name ds-id)
         :ascr ascr
         :complete complete?
         :budget_remaining budget-left
         :summary (str "Interview completed for " (name ds-id)
                       " with " (count messages) " messages exchanged. "
                       (if complete?
                         "DS completion criteria met."
                         (str "Budget exhausted with " budget-left " remaining.")))})

      (catch Exception e
        (log! :error (str "Error in conduct-interview: " (.getMessage e)))
        {:status "error"
         :error (.getMessage e)
         :ds_id (name ds-id)}))))

(defmethod tool-system/format-results :conduct-interview [_ result]
  (cond
    (= "error" (:status result))
    {:result [(str "Error conducting interview: " (:error result))]
     :error true}

    (= "success" (:status result))
    {:result [(json/write-str
               {:message-type "interview-completed"
                :status (:status result)
                :ds_id (:ds_id result)
                :ascr (:ascr result)
                :complete (:complete result)
                :budget_remaining (:budget_remaining result)
                :summary (:summary result)})]
     :error false}

    :else
    {:result [(json/write-str result)]
     :error false}))

;;; Helper to create all interviewer tools

(defn create-iviewr-tools
  "Return a vector of the tool configurations for each tool in this file."
  []
  [#_{:tool-type :formulate-question}
   #_{:tool-type :interpret-response}
   #_{:tool-type :get-current-ds}
   {:tool-type :conduct-interview}])
