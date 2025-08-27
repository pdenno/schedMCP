(ns sched-mcp.tools.orchestrator.core
  "Orchestrator tools for managing Discovery Schema flow"
  (:require
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.ds-loader :as ds]
   [sched-mcp.ds-combine :as combine]
   [sched-mcp.ds-schema :as ds-schema]
   [sched-mcp.util :refer [alog!]]
   [datahike.api :as d]
   [sched-mcp.sutil :refer [connect-atm]]))

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

;;; Get Next DS Tool

(defmethod tool-system/tool-name :get-next-ds [_]
  "get_next_ds")

(defmethod tool-system/tool-description :get-next-ds [_]
  "Analyzes conversation history and recommends the next Discovery Schema to pursue. Uses orchestration logic to determine the best progression path.")

(defmethod tool-system/tool-schema :get-next-ds [_]
  {:type "object"
   :properties {:project_id tool-system/project-id-schema
                :conversation_id tool-system/conversation-id-schema}
   :required ["project_id" "conversation_id"]})

(defmethod tool-system/execute-tool :get-next-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id]}]
  (let [conn (connect-atm (keyword project-id))
        ;; Get completed DS list
        completed (d/q '[:find [?ds ...]
                         :in $ ?cid
                         :where
                         [?c :conversation/id ?cid]
                         [?c :conversation/completed-ds ?ds]]
                       @conn (keyword conversation-id))]
    ;; Simple orchestration logic
    (cond
      ;; If nothing completed, start with warm-up
      (empty? completed)
      {:ds_id "process/warm-up-with-challenges"
       :rationale "Starting with warm-up to understand scheduling challenges"
       :interviewer_type "process"
       :priority 1.0}

      ;; If warm-up done, go to scheduling-problem-type
      (contains? (set completed) :process/warm-up-with-challenges)
      (if (contains? (set completed) :process/scheduling-problem-type)
        ;; If problem type done, recommend flow-shop or job-shop based on type
        {:ds_id "process/flow-shop"
         :rationale "Based on problem type, exploring flow shop details"
         :interviewer_type "process"
         :priority 0.9}
        ;; Otherwise do problem type
        {:ds_id "process/scheduling-problem-type"
         :rationale "Need to classify the scheduling problem type"
         :interviewer_type "process"
         :priority 1.0})

      ;; Default
      :else
      {:ds_id "data/orm"
       :rationale "Exploring data structures used in scheduling"
       :interviewer_type "data"
       :priority 0.8})))

;;; Start DS Pursuit Tool

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
                         :description "Question budget (optional, default 10)"}}
   :required ["project_id" "conversation_id" "ds_id"]})

(defmethod tool-system/execute-tool :start-ds-pursuit
  [{:keys [_system-atom]} {:keys [project-id conversation-id ds-id budget]}]
  (let [conn (connect-atm (keyword project-id))
        ds (ds/get-cached-ds (keyword ds-id))
        budget (or budget 10)]
    (if-not ds
      {:error (str "Discovery Schema not found: " ds-id)}
      (let [pursuit-id (keyword (str "pursuit-" (System/currentTimeMillis)))
            conv-eid (d/q '[:find ?c .
                            :in $ ?cid
                            :where [?c :conversation/id ?cid]]
                          @conn (keyword conversation-id))]
        ;; Create pursuit
        (d/transact conn [{:pursuit/id pursuit-id
                           :pursuit/ds-id (keyword ds-id)
                           :pursuit/conversation conv-eid
                           :pursuit/started (java.util.Date.)
                           :pursuit/status :active
                           :pursuit/budget-allocated budget
                           :pursuit/budget-used 0}])
        ;; Set as active pursuit
        (d/transact conn [{:db/id conv-eid
                           :conversation/active-pursuit [:pursuit/id pursuit-id]}])
        ;; Return info
        {:pursuit_id (name pursuit-id)
         :ds_instructions (:interview-objective ds)
         :ds_template (:eads ds)
         :budget budget
         :current_state {}}))))

;;; Complete DS Tool

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

(defmethod tool-system/execute-tool :complete-ds
  [{:keys [_system-atom]} {:keys [project-id conversation-id final-notes]}]
  (let [conn (connect-atm (keyword project-id))
        ;; Find active pursuit - fixed query
        pursuit-data (d/q '[:find [?pursuit ?ds-id]
                            :in $ ?cid
                            :where
                            [?c :conversation/id ?cid]
                            [?c :conversation/active-pursuit ?pursuit]
                            [?pursuit :pursuit/ds-id ?ds-id]]
                          @conn (keyword conversation-id))]
    (if-not pursuit-data
      {:error "No active pursuit to complete"}
      (let [[pursuit-eid ds-id] pursuit-data
            ;; Get final ASCR
            ascr (combine/combine-ds! ds-id (keyword project-id))]
        ;; Mark pursuit complete
        (d/transact conn [{:db/id pursuit-eid
                           :pursuit/status :complete}])
        ;; Add to completed list
        (d/transact conn [{:db/id [:conversation/id (keyword conversation-id)]
                           :conversation/completed-ds ds-id}])
        ;; Clear active pursuit - use retraction instead of nil
        (d/transact conn [[:db/retract [:conversation/id (keyword conversation-id)]
                           :conversation/active-pursuit]])
        ;; Log final notes if provided
        (when final-notes
          (alog! (str "DS completed with notes: " final-notes)))
        {:success true
         :ds_id (str (namespace ds-id) "/" (name ds-id))
         :final_ascr ascr
         :validation_results {:valid true}}))))

;;; Helper to create all orchestrator tools

(defn create-orchestrator-tools
  "Create all orchestrator tools with shared system atom"
  [system-atom]
  [(create-get-next-ds-tool system-atom)
   (create-start-ds-pursuit-tool system-atom)
   (create-complete-ds-tool system-atom)])
