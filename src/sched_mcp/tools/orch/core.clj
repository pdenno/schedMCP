(ns sched-mcp.tools.orch.core
  "Orchestrator tools for managing Discovery Schema flow"
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :refer [alog! log!]]
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [connect-atm]]))

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

(defmethod tool-system/validate-inputs :get-next-ds [_ inputs]
  (tool-system/validate-required-params inputs [:project_id :conversation_id]))

(defmethod tool-system/execute-tool :get-next-ds
  [_ {:keys [project_id conversation_id]}]
  (try
    (let [pid (keyword project_id)
          cid (keyword conversation_id)

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

(defmethod tool-system/validate-inputs :start-ds-pursuit [_ inputs]
  (tool-system/validate-required-params inputs [:project_id :conversation_id :ds_id]))

(defmethod tool-system/execute-tool :start-ds-pursuit
  [_ {:keys [project_id conversation_id ds_id budget] :or {budget 1.0}}]
  (let [pid (keyword project_id)
        cid (keyword conversation_id)
        ds-id (keyword ds_id)
        ds (sdb/get-DS-instructions ds-id)]
    (log! :info (str "orch_start_ds_pursuit " pid " " cid " " ds-id))
    (if (= ds "") ; get-DS-instructions returns empty string when not found
      {:error (str "Discovery Schema not found: " ds_id)}
      (try
        ;; Set the DS as active for this conversation
        (pdb/put-active-DS-id pid cid ds-id)
        ;; Initialize ASCR if it doesn't exist
        (when-not (pdb/ASCR-exists? pid ds-id)
          (pdb/init-ASCR! pid ds-id))
        ;; Return info about the DS
        {:ds_id (name ds-id)
         :interview_objective (:interview-objective ds)
         :ds_template (:DS ds)
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

(defmethod tool-system/validate-inputs :complete-ds [_ inputs]
  (tool-system/validate-required-params inputs [:project_id :conversation_id]))

(defmethod tool-system/execute-tool :complete-ds
  [_ {:keys [project_id conversation_id final_notes]}]
  (let [pid (keyword project_id)
        cid (keyword conversation_id)
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
        (when final_notes
          (log! :info (str "DS completed with notes: " final_notes)))
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

(defmethod tool-system/validate-inputs :get-progress [_ inputs]
  (tool-system/validate-required-params inputs [:project_id]))

(defmethod tool-system/execute-tool :get-progress
  [_ {:keys [project_id]}]
  (try
    (let [pid (keyword project_id)
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

;;; ========================================== DB tools for orchestrator decision support. ===

;;; --------------- db_query -----------------------------------
(defmethod tool-system/tool-name :db-query [_]
  "db_query")

(defmethod tool-system/tool-description :db-query [_]
  (str "Make a datalog-style query against either the current project DB, or the system DB."
       "For example, with `db_type`= `project` (running aginst the current project) and `query_string` = `[:find ?ds-id . :where [_ :project/active-DS-id ?ds-id]]`\n"
       "you would obtain the project's active discovery schema.\n"
       "Detailed instructions for using this tool are found in the resource ORCHESTRATOR_GUIDE.md"
       "Descriptions of the project and system database schemas are found in the MCP resource DB_SCHEMA.md."))

(defmethod tool-system/tool-schema :db-query [_]
  {:type "object"
   :properties {:db_type {:type "string"
                          :description "either \"project\" or \"system\""}
                :query_string {:type "string"
                               :description "Datomic-style datalog query e.g. \"[:find ?pid :where [_ :system/current-project  ?pid]\" for project orchestration decisions."}}
   :required ["db_type" "query_string"]})

;;; ToDo: Can/should this chceck for :db_type #{"project" "system"}?
(defmethod tool-system/validate-inputs :db-query [_ inputs]
  (tool-system/validate-required-params inputs [:db_type :query_string]))

(defmethod tool-system/execute-tool :db-query
  [_ {:keys [db_type query_string]}]
  (try
    (let [db-type (keyword db_type)
          query (edn/read-string query_string)
          id (if (= :system db-type)
               :system
               (sdb/get-current-project))]
      (when-not (= :system id)
        (when-not (pdb/project-exists? id)
          (throw (ex-info "No such project" {:id id}))))
      {:status "success"
       :query-result (d/q query @(connect-atm id))})
    (catch Exception e
      (log! :error (str "Error in db-query: " (.getMessage e)))
      {:status "error"
       :message (str "Error in db-query: " (.getMessage e))})))

(defmethod tool-system/format-results :db-query [_ result]
  (if (= "error" (:status result))
    {:result [(str "Error: " (:message result))]
     :error true}
    {:result [(json/write-str {:query_result (:query-result result)})]
     :error false}))

;;; --------------- db_resolve_entity -----------------------------------

(defmethod tool-system/tool-name :db-resolve-entity [_]
  "db_resolve_entity")

(defmethod tool-system/tool-description :db-resolve-entity [_]
  (str "Resolve a database entity ID into the tree (Clojure map) of the information at and below that entity in the given database, either \"project\" or \"system\"'./n"
       "It is typically used once you find the entity ID of interest using the tool db_query./n"
       "Detailed instructions for using this tool are found in the resource ORCHESTRATOR_GUIDE.md"
       "See also the MCP resource DB_SCHEMA.md"))

(defmethod tool-system/tool-schema :db-resolve-entity [_]
  {:type "object"
   :properties {:entity_id {:type "integer", :minimum 1 :description "a database entity id (positive integer) typically found using the db_query tool"}
                :db_type   {:type "string"
                            :description "either \"project\" or \"system\" meaning the current project DB and system DB respectively"}
                :keep-set  {:type "string"
                            :description (str "a stringified Clojure Set of DB schema attribute keywords, for example \"#{:system/DS :DS/id}\".\n"
                                              "The resulting tree will have only those branches (if those attributes can be reached from any active node in the tree).")}
                :drop-set  {:type "string"
                            :description (str "a stringified Clojure Set of DB schema attribute keywords, for example \"#{:db/id, :project/claims}\".\n"
                                              "The resulting tree will not contain these branches named by these attributes.")}}
   :required ["entity_id" "db_type"]})

;;; ToDo: Can/should this chceck for :db_type #{"project" "system"}?
(defmethod tool-system/validate-inputs :db-resolve-entity [_ inputs]
  (tool-system/validate-required-params inputs [:db_type :entity_id]))

(defmethod tool-system/execute-tool :db-resolve-entity
  [_ {:keys [db_type entity_id keep_set drop_set]}]
  (try
    (assert integer? entity_id)
    (let [db-type (keyword db_type)
          id (if (= :system db-type)
               :system
               (sdb/get-current-project))]
      (when-not (= :system id)
        (when-not (pdb/project-exists? id)
          (throw (ex-info "No such project" {:id id}))))
      {:status "success"
       :query-result (sutil/resolve-db-id
                      {:db/id entity_id}
                      (connect-atm id)
                      (cond-> {:drop-set #{}}
                        keep_set (assoc :keep-set (edn/read-string keep_set))
                        drop_set (assoc :drop-set (edn/read-string drop_set))))})
    (catch Exception e
      (log! :error (str "Error in db-resolve-entity MCP tool: " (.getMessage e)))
      {:status "error"
       :message (str "Error in db-resolve-entity: " (.getMessage e))})))

(defmethod tool-system/format-results :db-resolve-entity [_ result]
  (if (= "error" (:status result))
    {:result [(str "Error: " (:message result))]
     :error true}
    {:result [(json/write-str {:query_result (:query-result result)})]
     :error false}))

(defn create-orch-tools
  "Return the tool configurations for each tool."
  []
  [#_{:tool-type :get-next-ds} ; deprecated
   #_{:tool-type :start-ds-pursuit} ; deprecated
   #_{:tool-type :complete-ds} ; deprecated
   #_{:tool-type :get-progress} ; deprecated
   {:tool-type :db-query}
   {:tool-type :db-resolve-entity}])
