(ns sched-mcp.ds-combine
  "Discovery Schema combine and completion logic
   Implements the combine-ds! and ds-complete? multimethods"
  (:require
   [clojure.spec.alpha :as s]
   [clojure.edn :as edn]
   [datahike.api :as d]
   [sched-mcp.sutil :refer [connect-atm]]
   [sched-mcp.util :refer [log!]]))

;;; Multimethods for DS operations

(defmulti combine-ds!
  "Combine SCRs into ASCR for a specific DS type
   Returns the combined ASCR"
  (fn [ds-id _project-id] ds-id))

(defmulti ds-complete?
  "Check if a DS is complete
   Returns boolean"
  (fn [ds-id _project-id] ds-id))

;;; Helper functions

(defn strip-annotations
  "Remove comment annotations from DS data"
  [data]
  (cond
    (map? data)
    (reduce-kv (fn [m k v]
                 (cond
                   ;; If it has :val and :comment, just take :val
                   (and (map? v) (contains? v :val) (contains? v :comment))
                   (assoc m k (strip-annotations (:val v)))
                   ;; Otherwise recurse
                   :else
                   (assoc m k (strip-annotations v))))
               {}
               data)

    (sequential? data)
    (mapv strip-annotations data)

    :else
    data))

(defn get-stored-scrs
  "Get all SCRs for a DS from the database"
  [project-id ds-id]
  (let [conn (connect-atm project-id)]
    ;; Find all messages with SCRs for this DS
    (let [scrs (d/q '[:find [?scr ...]
                      :in $ ?ds-id
                      :where
                      [?p :pursuit/ds-id ?ds-id]
                      [?m :message/pursuit ?p]
                      [?m :message/scr ?scr]]
                    @conn ds-id)]
      ;; Parse EDN strings back to data
      (mapv edn/read-string scrs))))

(defn get-ascr
  "Get current ASCR for a DS"
  [project-id ds-id]
  (let [conn (connect-atm project-id)
        ascr-str (d/q '[:find ?data .
                        :in $ ?ds-id
                        :where
                        [?a :ascr/ds-id ?ds-id]
                        [?a :ascr/data ?data]]
                      @conn ds-id)]
    (if ascr-str
      (edn/read-string ascr-str)
      {})))

(defn store-ascr!
  "Store updated ASCR in database"
  [project-id ds-id ascr]
  (let [conn (connect-atm project-id)
        ascr-id (keyword (str "ascr-" (name ds-id) "-" (System/currentTimeMillis)))
        ;; Check if ASCR already exists
        existing (d/q '[:find ?e .
                        :in $ ?ds-id
                        :where
                        [?e :ascr/ds-id ?ds-id]]
                      @conn ds-id)]
    (if existing
      ;; Update existing
      (d/transact conn [{:db/id existing
                         :ascr/data (pr-str ascr)
                         :ascr/updated (java.util.Date.)
                         :ascr/version (inc (or (d/q '[:find ?v .
                                                       :in $ ?e
                                                       :where [?e :ascr/version ?v]]
                                                     @conn existing) 0))}])
      ;; Create new
      (d/transact conn [{:ascr/id ascr-id
                         :ascr/ds-id ds-id
                         :ascr/data (pr-str ascr)
                         :ascr/version 1
                         :ascr/updated (java.util.Date.)}]))
    ascr))

;;; Generic merge strategies

(defn merge-latest
  "Simple merge taking latest values"
  [scrs]
  (reduce merge {} scrs))

(defn merge-with-append
  "Merge that appends to collections instead of replacing"
  [scrs]
  (reduce (fn [ascr scr]
            (merge-with (fn [old new]
                          (cond
                            ;; Both are collections - append
                            (and (coll? old) (coll? new))
                            (distinct (concat old new))
                            ;; Keep new value
                            :else new))
                        ascr scr))
          {}
          scrs))

(defn merge-with-conflict-detection
  "Merge that tracks conflicts for manual resolution"
  [scrs]
  (let [base (first scrs)
        conflicts (atom {})]
    (reduce (fn [ascr scr]
              (merge-with (fn [old new]
                            (if (and (not= old new)
                                     (not (nil? old))
                                     (not (nil? new)))
                              ;; Conflict detected
                              (do
                                (swap! conflicts assoc
                                       (gensym "conflict-")
                                       {:old old :new new})
                                new) ; Take new value
                              new))
                          ascr scr))
            base
            (rest scrs))))

;;; Default implementations

(defmethod combine-ds! :default
  [ds-id project-id]
  (log! :info (str "Using default combine for " ds-id))
  (let [scrs (get-stored-scrs project-id ds-id)
        ascr (merge-latest scrs)]
    (store-ascr! project-id ds-id ascr)
    ascr))

(defmethod ds-complete? :default
  [ds-id project-id]
  (log! :info (str "Using default complete check for " ds-id))
  false)

;;; Specific DS implementations

;; Warm-up with challenges
(defmethod combine-ds! :process/warm-up-with-challenges
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; For warm-up, merge latest but append challenges
        ascr (reduce (fn [acc scr]
                       (merge-with (fn [old new]
                                     (cond
                                       ;; Append scheduling challenges
                                       (= :scheduling-challenges (first (keys {old new})))
                                       (distinct (concat (if (coll? old) old [old])
                                                         (if (coll? new) new [new])))
                                       ;; Otherwise take latest
                                       :else new))
                                   acc scr))
                     {}
                     scrs)]
    (store-ascr! project-id ds-id ascr)
    ascr))

(defmethod ds-complete? :process/warm-up-with-challenges
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; Complete when all three fields have values
    (and (seq (:scheduling-challenges ascr))
         (some? (:product-or-service-name ascr))
         (some? (:one-more-thing ascr)))))

;; Scheduling problem type
(defmethod combine-ds! :process/scheduling-problem-type
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; Merge, taking latest values
        merged (reduce merge {} scrs)
        ;; Ensure enums are keywords and handle problem-components specially
        ascr (-> merged
                 (update :principal-problem-type
                         #(when % (keyword %)))
                 (update :problem-components
                         (fn [comps]
                           ;; Collect all mentioned components
                           (distinct
                            (mapcat (fn [scr]
                                      (let [c (:problem-components scr)]
                                        (cond
                                          (sequential? c) (map keyword c)
                                          (some? c) [(keyword c)]
                                          :else [])))
                                    scrs)))))]
    (store-ascr! project-id ds-id ascr)
    ascr))

(defmethod ds-complete? :process/scheduling-problem-type
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; Complete when we have principal type and booleans answered
    (and (some? (:principal-problem-type ascr))
         (contains? ascr :continuous?)
         (contains? ascr :cyclical?))))

;; Flow shop
(defmethod combine-ds! :process/flow-shop
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; Complex merge for hierarchical subprocess data
        ascr (reduce (fn [acc scr]
                       (merge-with
                        (fn [old new]
                          (cond
                            ;; Special handling for subprocesses
                            (and (map? old)
                                 (contains? old :process-id)
                                 (map? new)
                                 (contains? new :process-id))
                            (merge old new) ; Merge subprocess details

                            ;; Append to subprocess lists
                            (and (sequential? old)
                                 (every? #(contains? % :process-id) old))
                            (let [old-ids (set (map :process-id old))
                                  new-items (filter #(not (old-ids (:process-id %)))
                                                    (if (sequential? new) new [new]))]
                              (concat old new-items))

                            :else new))
                        acc scr))
                     {}
                     scrs)]
    (store-ascr! project-id ds-id ascr)
    ascr))

(defmethod ds-complete? :process/flow-shop
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; Check exhausted flag or minimum subprocess count
    (or (:exhausted? ascr false)
        (>= (count (:subprocesses ascr [])) 3))))

;; ORM (data domain)
(defmethod combine-ds! :data/orm
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; ORM needs special handling for fact-types
        ascr (reduce (fn [acc scr]
                       (merge-with
                        (fn [old new]
                          (cond
                            ;; Append fact-types
                            (and (sequential? old)
                                 (every? #(contains? % :fact-type-id) old))
                            (concat old (if (sequential? new) new [new]))

                            ;; Merge inquiry areas
                            (and (map? old)
                                 (contains? old :inquiry-areas))
                            (update old :inquiry-areas
                                    #(distinct (concat % (:inquiry-areas new))))

                            :else new))
                        acc scr))
                     {}
                     scrs)]
    (store-ascr! project-id ds-id ascr)
    ascr))

(defmethod ds-complete? :data/orm
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; ORM uses exhausted flag
    (:exhausted? ascr false)))

;;; Utility functions for tools

(defn merge-scr-into-ascr
  "Merge a new SCR into existing ASCR using DS-specific logic"
  [ds-id existing-ascr new-scr]
  ;; Delegate to the multimethod after creating temporary SCR list
  (let [temp-scrs (if (empty? existing-ascr)
                    [new-scr]
                    [existing-ascr new-scr])]
    ;; Use the DS-specific combine logic
    (case (namespace ds-id)
      "process" (case (name ds-id)
                  "warm-up-with-challenges"
                  (combine-ds! :process/warm-up-with-challenges nil)
                  "scheduling-problem-type"
                  (combine-ds! :process/scheduling-problem-type nil)
                  ;; Default
                  (merge existing-ascr new-scr))
      "data" (merge existing-ascr new-scr)
      ;; Default
      (merge existing-ascr new-scr))))

(defn validate-scr
  "Validate an SCR against DS structure"
  [ds scr]
  ;; TODO: Implement validation
  true)
