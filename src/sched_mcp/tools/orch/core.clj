(ns sched-mcp.tools.orch.core
  "Orchestrator tools for managing Discovery Schema flow"
  (:require
   [clojure.data.json :as json]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :refer [alog! log!]]
   [datahike.api :as d]
   [sched-mcp.sutil :refer [connect-atm]])) ; <================================================= FIX THIS

;;; Tool configurations

(defn create-get-next-ds-tool
  "Creates the tool for recommending next DS"
  [system-atom]
  {:tool-type :get-next-ds
   :system-atom system-atom})

(defn create-start-ds-pursuit-tool
  "Creates the tool for starting DS pursuit"
  [system-atom]
  {:tool-type :start-ds-pursuit
   :system-atom system-atom})

(defn create-complete-ds-tool
  "Creates the tool for completing DS"
  [system-atom]
  {:tool-type :complete-ds
   :system-atom system-atom})

(defn create-get-progress-tool
  "Creates the tool for getting overall interview progress"
  [system-atom]
  {:tool-type :get-progress
   :system-atom system-atom})

;;; Get Next DS Tool

(defmethod tool-system/tool-name :get-next-ds [_]
  "orch_get_next_ds")

;;; ToDo: This seems quite inadequate. Should talk about SCRs and ASCR.
(defmethod tool-system/tool-description :get-next-ds [_]
  (str "Get comprehensive Discovery Schema status and data for orchestration decisions. "
       "Returns all available DS with their completion status, ASCRs, and interview objectives. "
       "Use the MCP orchestrator guide to analyze this data and make recommendations."))

;;; ToDo: Verify that conversation ID is simply one of :process, :data :resource, or :optimality
(defmethod tool-system/tool-schema :get-next-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/execute-tool :get-next-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id]}]
  (try
    (let [pid (keyword project-id)
          cid (keyword conversation-id)

          ;; Get all available DS from system DB
          all-ds (sdb/system-DS?)

          ;; Get all ASCRs for this project
          project-ascrs (pdb/list-ASCR pid)

          ;; Get completed DS (those with completed ASCRs)
          completed-ds (into #{}
                             (filter #(let [ascr (pdb/get-ASCR pid %)]
                                        (and ascr (:ascr/completed? ascr)))
                                     project-ascrs))

          ;; Get current active DS
          current-ds (pdb/get-active-DS-id pid cid)

          ;; Get DS in progress (has ASCR but not completed)
          in-progress-ds (into #{}
                               (filter #(let [ascr (pdb/get-ASCR pid %)]
                                          (and ascr (not (:ascr/completed? ascr))))
                                       project-ascrs))

          ;; Build detailed info for each DS
          ds-details (map (fn [ds-id]
                            (let [ds-info (sdb/get-DS-instructions ds-id)
                                  ascr (when (contains? (set project-ascrs) ds-id)
                                         (pdb/get-ASCR pid ds-id))
                                  status (cond
                                           (contains? completed-ds ds-id) "completed"
                                           (contains? in-progress-ds ds-id) "in-progress"
                                           (= current-ds ds-id) "active"
                                           :else "not-started")]
                              {:ds_id (if (keyword? ds-id) (name ds-id) (str ds-id))
                               :status status
                               :interview_objective (get-in ds-info [:DS :interview-objective])
                               :budget_remaining (when ascr (:ascr/budget-left ascr))
                               :ascr_summary (when ascr
                                               (dissoc (:ascr/dstruct ascr) :comment))}))
                          all-ds)]

      ;; Return comprehensive data for orchestration decision
      {:available_ds ds-details
       :completed_count (count completed-ds)
       :total_available (count all-ds)
       :current_active_ds (when current-ds (if (keyword? current-ds) (name current-ds) current-ds))
       :current_conversation (name cid)
       :project_ASCRs (into {}
                            (map (fn [ds-id]
                                   (let [ascr (pdb/get-ASCR pid ds-id)]
                                     [(if (keyword? ds-id) (name ds-id) (str ds-id)) (:ascr/dstruct ascr)]))
                                 project-ascrs))
       :recommendation_needed true
       :orchestrator_guide_available true})
    (catch Exception e
      (log! :error (str "Error in get-next-ds: " (.getMessage e)))
      {:error (str "Failed to get next DS: " (.getMessage e))})))

;;; Start DS Pursuit Tool

(defmethod tool-system/tool-name :start-ds-pursuit [_]
  "orch_start_ds_pursuit")

(defmethod tool-system/tool-description :start-ds-pursuit [_]
  "Begins working on a specific Discovery Schema. Initializes pursuit tracking and returns DS instructions.")

(defmethod tool-system/tool-schema :start-ds-pursuit [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :ds_id tool-system/ds-id-schema
                :budget {:type "integer"
                         :description "Question budget (optional, default 10)"}}
   :required ["project_id" "conversation_id" "ds_id"]})

(defmethod tool-system/execute-tool :start-ds-pursuit
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id budget]}]
  (let [pid (keyword project-id)
        cid (keyword conversation-id)
        ds-id-kw (keyword ds-id)
        ds (sdb/get-DS-instructions ds-id-kw)
        budget (or budget 1.0)]
    (alog! (str "orch_start_ds_pursuit " pid " " cid " " ds-id))
    (if (= ds "") ; get-DS-instructions returns empty string when not found
      {:error (str "Discovery Schema not found: " ds-id)}
      (try
        ;; Set the DS as active for this conversation
        (pdb/put-active-DS-id pid cid ds-id-kw)

        ;; Initialize ASCR if it doesn't exist
        (when-not (pdb/ASCR-exists? pid ds-id-kw)
          (pdb/init-ASCR! pid ds-id-kw)
          ;; Set initial budget
          (let [conn (connect-atm pid)
                ascr-eid (d/q '[:find ?e .
                                :in $ ?ds-id
                                :where [?e :ascr/id ?ds-id]]
                              @conn ds-id-kw)]
            (d/transact conn [{:db/id ascr-eid
                               :ascr/budget-left (double budget)}])))

        ;; Return info about the DS
        {:ds_id (name ds-id-kw)
         :interview_objective (:interview-objective ds)
         :ds_template (:DS ds) ; The DS structure itself, not nested under EADS
         :budget budget
         :status "Started DS pursuit"}
        (catch Exception e
          (log! :error (str "Error in start-ds-pursuit: " (.getMessage e)))
          {:error (str "Failed to start DS pursuit: " (.getMessage e))})))))

;;; Complete DS Tool

(defmethod tool-system/tool-name :complete-ds [_]
  "orch_complete_ds")

(defmethod tool-system/tool-description :complete-ds [_]
  "Marks a Discovery Schema pursuit as complete and stores the final ASCR.")

(defmethod tool-system/tool-schema :complete-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema
                :final_notes {:type "string"
                              :description "Completion notes"}}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/execute-tool :complete-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id final-notes]}]
  (let [pid (keyword project-id)
        cid (keyword conversation-id)
        conn (connect-atm pid)
        ;; Get the active DS from conversation
        active-ds (pdb/get-active-DS-id pid cid)]
    (alog! (str "orch_complete_ds " pid " " cid))
    (if-not active-ds
      {:error "No active DS to complete"}
      (let [;; Get the current ASCR (which is already up-to-date)
            ascr (pdb/get-ASCR pid active-ds)]
        ;; Mark ASCR as complete
        (pdb/mark-ASCR-complete! pid active-ds)
        ;; Add to completed DS list for the conversation
        (d/transact conn [{:db/id [:conversation/id cid]
                           :conversation/completed-ds active-ds}])
        ;; Clear active DS
        (d/transact conn [[:db/retract [:conversation/id cid]
                           :conversation/active-DS-id]])
        ;; Log final notes if provided
        (when final-notes
          (log! :info (str "DS completed with notes: " final-notes)))
        {:success true
         :ds_id (str (namespace active-ds) "/" (name active-ds))
         :final_ascr ascr
         :validation_results {:valid true}}))))

;;; Get Progress Tool

(defmethod tool-system/tool-name :get-progress [_]
  "orch_get_progress")

(defmethod tool-system/tool-description :get-progress [_]
  "Returns overall interview progress including completed Discovery Schemas and current phase.")

(defmethod tool-system/tool-schema :get-progress [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema}
   :required ["project_id"]})

(defmethod tool-system/execute-tool :get-progress
  [{:keys [_system-atom]} {:keys [project-id]}]
  (try
    (let [pid (keyword project-id)
          progress (pdb/get-interview-progress pid)]
      (alog! (str "orch_get_progress " pid))
      progress)
    (catch Exception e
      (log! :error (str "Error in get-progress: " (.getMessage e)))
      {:error (.getMessage e)})))

;;; Format results methods for orchestrator tools

(defmethod tool-system/format-results :get-next-ds [_ result]
  (cond
    (:error result)
    {:result [(str "Error getting next DS: " (:error result))]
     :error true}

    (:available_ds result)
    {:result [(json/write-str
               {:message-type "orchestration-status"
                :available_ds (:available_ds result)
                :completed_count (:completed_count result)
                :total_available (:total_available result)
                :current_active_ds (:current_active_ds result)
                :current_conversation (:current_conversation result)
                :project_ASCRs (:project_ASCRs result)
                :recommendation_needed (:recommendation_needed result)
                :orchestrator_guide_available (:orchestrator_guide_available result)})]
     :error false}

    :else
    {:result [(json/write-str result)]
     :error false}))

(defmethod tool-system/format-results :start-ds-pursuit [_ result]
  (cond
    (:error result)
    {:result [(str "Error starting DS pursuit: " (:error result))]
     :error true}

    (:ds_id result)
    {:result [(json/write-str
               {:message-type "ds-pursuit-started"
                :ds_id (:ds_id result)
                :interview_objective (:interview_objective result)
                :ds_template (:ds_template result)
                :budget (:budget result)
                :status (:status result)})]
     :error false}

    :else
    {:result [(json/write-str result)]
     :error false}))

(defmethod tool-system/format-results :complete-ds [_ result]
  (cond
    (:error result)
    {:result [(str "Error completing DS: " (:error result))]
     :error true}

    (:success result)
    {:result [(json/write-str
               {:message-type "ds-completed"
                :success (:success result)
                :ds_id (:ds_id result)
                :final_ascr (:final_ascr result)
                :validation_results (:validation_results result)})]
     :error false}

    :else
    {:result [(json/write-str result)]
     :error false}))

(defmethod tool-system/format-results :get-progress [_ result]
  (cond
    (:error result)
    {:result [(str "Error getting progress: " (:error result))]
     :error true}

    ;; Progress result structure varies, so just pass it through as JSON
    :else
    {:result [(json/write-str
               (merge {:message-type "interview-progress"}
                      result))]
     :error false}))

;;; Helper to create all orchestrator tools

(defn create-orchestrator-tools
  "Create all orchestrator tools with shared system atom"
  [system-atom]
  [(create-get-next-ds-tool system-atom)
   (create-start-ds-pursuit-tool system-atom)
   (create-complete-ds-tool system-atom)
   (create-get-progress-tool system-atom)])
