(ns sched-mcp.ds-combine
  "Discovery Schema combine and completion logic
   Implements the combine-ds! and ds-complete? multimethods"
  (:require
   [clojure.spec.alpha :as s]
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
      (mapv read-string scrs))))

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
      (read-string ascr-str)
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

;;; Default implementations

(defmethod combine-ds! :default
  [ds-id project-id]
  (log! :info (str "Using default combine for " ds-id))
  (let [scrs (get-stored-scrs project-id ds-id)]
    ;; Simple merge for default
    (reduce merge {} scrs)))

(defmethod ds-complete? :default
  [ds-id project-id]
  (log! :info (str "Using default complete check for " ds-id))
  false)

;;; Specific DS implementations

;; Warm-up with challenges
(defmethod combine-ds! :process/warm-up-with-challenges
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; For warm-up, we just need the latest answers
        merged (reduce merge {} scrs)]
    (store-ascr! project-id ds-id merged)
    merged))

(defmethod ds-complete? :process/warm-up-with-challenges
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; Complete when all three questions answered
    (and (contains? ascr :scheduling-challenges)
         (contains? ascr :product-or-service-name)
         (contains? ascr :one-more-thing))))

;; Scheduling problem type
(defmethod combine-ds! :process/scheduling-problem-type
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; Merge, taking latest values
        merged (reduce merge {} scrs)
        ;; Ensure enums are keywords
        merged (-> merged
                   (update :principal-problem-type keyword)
                   (update :problem-components
                           #(mapv keyword (or % []))))]
    (store-ascr! project-id ds-id merged)
    merged))

(defmethod ds-complete? :process/scheduling-problem-type
  [ds-id project-id]
  ;; Always complete - simple classification
  true)

;; Flow shop
(defmethod combine-ds! :process/flow-shop
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)
        ;; More complex merge for hierarchical data
        base (first scrs)
        rest-scrs (rest scrs)]
    ;; TODO: Implement subprocess merging logic
    (reduce merge base rest-scrs)))

(defmethod ds-complete? :process/flow-shop
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; Check exhausted flag
    (:exhausted? ascr false)))

;; ORM (data domain)
(defmethod combine-ds! :data/orm
  [ds-id project-id]
  (let [scrs (get-stored-scrs project-id ds-id)]
    ;; ORM has complex merging with inquiry areas
    ;; For now, simple merge
    (reduce merge {} scrs)))

(defmethod ds-complete? :data/orm
  [ds-id project-id]
  (let [ascr (get-ascr project-id ds-id)]
    ;; ORM uses exhausted flag
    (:exhausted? ascr false)))

;;; Utility functions for tools

(defn merge-scr-into-ascr
  "Merge a new SCR into existing ASCR"
  [ascr scr]
  ;; Simple merge for now - can be made more sophisticated
  (merge ascr scr))

(defn validate-scr
  "Validate an SCR against DS structure"
  [ds scr]
  ;; TODO: Implement validation
  true)
