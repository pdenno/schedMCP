(ns sched-mcp.orchestration
  "Advanced Discovery Schema orchestration for multi-phase interviews
   Week 3 Day 4: Advanced Interview Flows"
  (:require
   [clojure.string :as str]
   [datahike.api :as d]
   [sched-mcp.ds-loader :as ds-loader]
   [sched-mcp.ds-combine :as combine]
   [sched-mcp.sutil :refer [connect-atm]]
   [sched-mcp.util :refer [log!]]))

;; Discovery Schema Flow Graph
;; Defines the progression through different DS phases
(def ds-flow-graph
  "Defines valid transitions between Discovery Schemas.
   Each DS can lead to multiple possible next DS options."
  {:process/warm-up-with-challenges
   {:next [:process/scheduling-problem-type]
    :description "Initial warm-up leads to problem type classification"}

   :process/scheduling-problem-type
   {:next [:process/flow-shop
           :process/job-shop
           :process/timetabling]
    :description "Problem type determines specific process DS"}

   :process/flow-shop
   {:next [:data/orm :resource/equipment]
    :description "Flow shop leads to data modeling or equipment resources"}

   :process/job-shop
   {:next [:process/job-shop--unique
           :process/job-shop--classifiable]
    :description "Job shop branches into unique or classifiable jobs"}

   :process/timetabling
   {:next [:data/orm :resource/human-resources]
    :description "Timetabling typically needs data and human resources"}

   :data/orm
   {:next [:optimization/objectives]
    :description "Data model leads to optimization objectives"}})

(defn get-next-ds-options
  "Get possible next Discovery Schemas based on current DS"
  [current-ds-id]
  (get-in ds-flow-graph [current-ds-id :next] []))

(defn get-active-pursuit
  "Get the currently active DS pursuit for a project/conversation"
  [project-id conversation-id]
  (let [conn (connect-atm project-id)
        result (d/q '[:find ?e ?ds-id ?status ?budget-used ?budget-allocated
                      :in $ ?conv-id
                      :where
                      [?e :pursuit/conversation-id ?conv-id]
                      [?e :pursuit/status :active] ; Only active pursuits
                      [?e :pursuit/ds-id ?ds-id]
                      [?e :pursuit/budget-used ?budget-used]
                      [?e :pursuit/budget-allocated ?budget-allocated]
                      :bind [?status :active]] ; Since we know it's active
                    @conn conversation-id)]
    (when-let [[eid ds-id status used allocated] (first result)]
      {:entity-id eid
       :ds-id ds-id
       :status status
       :budget-used used
       :budget-allocated allocated
       :budget-remaining (- allocated used)})))

(defn get-completed-ds
  "Get all completed Discovery Schemas for a project"
  [project-id]
  (let [conn (connect-atm project-id)]
    (->> (d/q '[:find ?ds-id ?completed-at
                :where
                [?p :pursuit/status :complete]
                [?p :pursuit/ds-id ?ds-id]
                [?p :pursuit/completed-at ?completed-at]]
              @conn)
         (map (fn [[ds-id completed-at]]
                {:ds-id ds-id :completed-at completed-at}))
         (sort-by :completed-at))))

(defn calculate-ds-priority
  "Calculate priority score for a DS option based on:
   - Dependencies satisfied
   - Information gaps
   - User preferences"
  [ds-id project-id completed-ds-ids]
  (let [base-priority (case ds-id
                        ;; Higher priority for data collection
                        :data/orm 90
                        :process/flow-shop 80
                        :process/job-shop 80
                        :process/timetabling 80
                        ;; Lower priority for detailed variants
                        :process/job-shop--unique 60
                        :process/job-shop--classifiable 60
                        50)
        ;; Boost if prerequisites are met
        prereq-bonus (if (contains? completed-ds-ids :process/scheduling-problem-type)
                       20 0)
        ;; Penalty if too many process DS already done
        process-penalty (if (> (count (filter #(str/starts-with? (name %) "process")
                                              completed-ds-ids)) 3)
                          -20 0)]
    (+ base-priority prereq-bonus process-penalty)))

(defn recommend-next-ds
  "Recommend the next Discovery Schema based on:
   1. Current DS completion
   2. Valid flow graph transitions
   3. Information already collected
   4. Strategic priorities"
  [project-id conversation-id]
  (let [conn (connect-atm project-id)
        ;; Get current state
        active-pursuit (get-active-pursuit project-id conversation-id)
        completed (get-completed-ds project-id)
        completed-ids (set (map :ds-id completed))

        ;; Determine candidates
        last-completed-ds (-> completed last :ds-id)
        candidates (if last-completed-ds
                     (get-next-ds-options last-completed-ds)
                     [:process/warm-up-with-challenges])]

    ;; If active pursuit exists and not complete, continue with it
    (if (and active-pursuit (= (:status active-pursuit) :active))
      {:recommendation :continue-current
       :ds-id (:ds-id active-pursuit)
       :reason "Current DS pursuit is still active"
       :budget-remaining (:budget-remaining active-pursuit)}

      ;; Otherwise, recommend next DS
      (let [;; Filter out already completed
            available (remove completed-ids candidates)
            ;; Calculate priorities
            scored (map (fn [ds-id]
                          {:ds-id ds-id
                           :priority (calculate-ds-priority ds-id project-id completed-ids)})
                        available)
            ;; Sort by priority
            ranked (sort-by :priority > scored)]

        (if-let [best (first ranked)]
          {:recommendation :start-new
           :ds-id (:ds-id best)
           :priority (:priority best)
           :reason (get-in ds-flow-graph [(:ds-id best) :description])
           :alternatives (map :ds-id (rest ranked))}

          ;; No more DS to pursue
          {:recommendation :interview-complete
           :reason "All relevant Discovery Schemas have been completed"
           :completed-count (count completed)})))))

(defn get-interview-progress
  "Get overall interview progress across all DS"
  [project-id]
  (let [completed (get-completed-ds project-id)
        completed-ids (set (map :ds-id completed))
        ;; Estimate total DS needed (varies by problem type)
        estimated-total (cond
                          (contains? completed-ids :process/timetabling) 4
                          (contains? completed-ids :process/job-shop) 5
                          (contains? completed-ids :process/flow-shop) 4
                          :else 3)
        progress-pct (int (* 100 (/ (count completed) estimated-total)))]

    {:completed-ds completed-ids
     :completed-count (count completed)
     :estimated-total estimated-total
     :progress-percentage progress-pct
     :current-phase (cond
                      (empty? completed) :warm-up
                      (< (count completed) 2) :process-discovery
                      (< (count completed) 4) :data-modeling
                      :else :optimization-setup)}))

(defn start-ds-pursuit
  "Start pursuing a new Discovery Schema"
  [project-id conversation-id ds-id budget]
  (let [conn (connect-atm project-id)]
    ;; Mark any active pursuits as abandoned (but not completed ones!)
    (when-let [active (get-active-pursuit project-id conversation-id)]
      (when (= (:status active) :active) ; Only abandon if still active
        (d/transact conn [{:db/id (:entity-id active)
                           :pursuit/status :abandoned
                           :pursuit/abandoned-at (java.util.Date.)}])))

    ;; Create new pursuit
    (d/transact conn [{:pursuit/id (keyword (str (name ds-id) "-" (System/currentTimeMillis)))
                       :pursuit/conversation-id conversation-id
                       :pursuit/ds-id ds-id
                       :pursuit/status :active
                       :pursuit/started-at (java.util.Date.)
                       :pursuit/budget-allocated budget
                       :pursuit/budget-used 0}])

    (log! :info (str "Started DS pursuit: " ds-id " for conversation " conversation-id))

    ;; Return DS instructions
    (when-let [ds (ds-loader/get-cached-ds ds-id)]
      {:ds-id ds-id
       :instructions (:interview-objective ds)
       :eads (:eads ds)
       :budget budget})))

(defn complete-ds-pursuit
  "Mark a DS pursuit as complete"
  [project-id conversation-id notes]
  (when-let [active (get-active-pursuit project-id conversation-id)]
    (let [conn (connect-atm project-id)]
      (d/transact conn [{:db/id (:entity-id active)
                         :pursuit/status :complete
                         :pursuit/completed-at (java.util.Date.)
                         :pursuit/completion-notes notes}])

      (log! :info (str "Completed DS pursuit: " (:ds-id active)))

      ;; Get next recommendation
      (recommend-next-ds project-id conversation-id))))

;; Schema for orchestration entities
(def orchestration-schema
  [{:db/ident :pursuit/id
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/conversation-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/ds-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Status: :active, :complete, :abandoned"}

   {:db/ident :pursuit/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/completed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/abandoned-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/budget-allocated
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/budget-used
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :pursuit/completion-notes
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

;; Initialize orchestration schema when namespace loads
(defn init-orchestration-schema!
  "Add orchestration schema to project databases"
  [project-id]
  (let [conn (connect-atm project-id)]
    (d/transact conn orchestration-schema)
    (log! :info (str "Initialized orchestration schema for " project-id))))
