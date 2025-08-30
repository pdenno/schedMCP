(ns sched-mcp.tools.interviewer.advanced
  "Advanced interviewer tools for multi-phase DS orchestration
   Week 3 Day 4: Advanced Interview Flows"
  (:require
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.orchestration :as orch]
   [sched-mcp.util :refer [log!]]))

;; Tool 1: Get Next DS
(defn create-get-next-ds-tool
  "Tool for getting the next recommended Discovery Schema"
  [system-atom]
  {:tool-type :get-next-ds
   :system-atom system-atom})

(defmethod tool-system/tool-name :get-next-ds [_]
  "get_next_ds")

(defmethod tool-system/tool-description :get-next-ds [_]
  "Analyzes conversation history and recommends the next Discovery Schema to pursue. Uses orchestration logic to determine the best progression path.")

(defmethod tool-system/tool-schema :get-next-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/validate-inputs :get-next-ds [_ inputs]
  (tool-system/validate-required-params inputs [:project-id :conversation-id]))

(defmethod tool-system/execute-tool :get-next-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id]}]
  (try
    (let [recommendation (orch/recommend-next-ds
                          (keyword project-id)
                          (keyword conversation-id))
          progress (orch/get-interview-progress (keyword project-id))]

      (log! :info (str "DS recommendation for " project-id ": "
                  (:recommendation recommendation) " - " (:ds-id recommendation)))

      (merge recommendation
             {:progress progress}))
    (catch Exception e
      (log! :info (str "Error in get-next-ds: " (.getMessage e)) {:level :error})
      {:error (.getMessage e)})))

;; Tool 2: Start DS Pursuit
(defn create-start-ds-pursuit-tool
  "Tool for starting a new Discovery Schema pursuit"
  [system-atom]
  {:tool-type :start-ds-pursuit
   :system-atom system-atom})

(defmethod tool-system/tool-name :start-ds-pursuit [_]
  "start_ds_pursuit")

(defmethod tool-system/tool-description :start-ds-pursuit [_]
  "Begins working on a specific Discovery Schema. Initializes pursuit tracking and returns DS instructions.")

(defmethod tool-system/tool-schema :start-ds-pursuit [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :ds_id tool-system/ds-id-schema
                :budget {:type "integer"
                         :description "Question budget (optional, default 10)"
                         :minimum 1
                         :maximum 50}}
   :required ["project_id" "conversation_id" "ds_id"]})

(defmethod tool-system/validate-inputs :start-ds-pursuit [_ inputs]
  (tool-system/validate-required-params inputs
                                        [:project-id :conversation-id :ds-id]))

(defmethod tool-system/execute-tool :start-ds-pursuit
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id budget]}]
  (try
    ;; Initialize orchestration schema if needed
    (orch/init-orchestration-schema! (keyword project-id))

    (let [budget (or budget 10)
          result (orch/start-ds-pursuit
                  (keyword project-id)
                  (keyword conversation-id)
                  (keyword ds-id)
                  budget)]

      (if result
        (assoc result :status "Started new DS pursuit")
        {:error (str "Could not find Discovery Schema: " ds-id)}))
    (catch Exception e
      (log! :info (str "Error in start-ds-pursuit: " (.getMessage e)) {:level :error})
      {:error (.getMessage e)})))

;; Tool 3: Complete DS
(defn create-complete-ds-tool
  "Tool for marking a Discovery Schema as complete"
  [system-atom]
  {:tool-type :complete-ds
   :system-atom system-atom})

(defmethod tool-system/tool-name :complete-ds [_]
  "complete_ds")

(defmethod tool-system/tool-description :complete-ds [_]
  "Marks a Discovery Schema pursuit as complete and stores the final ASCR.")

(defmethod tool-system/tool-schema :complete-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :final_notes {:type "string"
                              :description "Completion notes"}}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/validate-inputs :complete-ds [_ inputs]
  (tool-system/validate-required-params inputs [:project-id :conversation-id]))

(defmethod tool-system/execute-tool :complete-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id final-notes]}]
  (try
    (let [notes (or final-notes "DS completed successfully")
          next-rec (orch/complete-ds-pursuit
                    (keyword project-id)
                    (keyword conversation-id)
                    notes)]

      (log! :info (str "Completed DS pursuit for " project-id))

      {:status "DS marked as complete"
       :next-recommendation next-rec})
    (catch Exception e
      (log! :info (str "Error in complete-ds: " (.getMessage e)) {:level :error})
      {:error (.getMessage e)})))

;; Tool 4: Get Interview Progress
(defn create-get-interview-progress-tool
  "Tool for getting overall interview progress"
  [system-atom]
  {:tool-type :get-interview-progress
   :system-atom system-atom})

(defmethod tool-system/tool-name :get-interview-progress [_]
  "get_interview_progress")

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
    (let [progress (orch/get-interview-progress (keyword project-id))]
      progress)
    (catch Exception e
      (log! :info (str "Error in get-interview-progress: " (.getMessage e))
             {:level :error})
      {:error (.getMessage e)})))

;; Create all advanced tools
(defn create-advanced-interviewer-tools
  "Create all advanced interviewer tools"
  [system-atom]
  [(create-get-next-ds-tool system-atom)
   (create-start-ds-pursuit-tool system-atom)
   (create-complete-ds-tool system-atom)
   (create-get-interview-progress-tool system-atom)])
