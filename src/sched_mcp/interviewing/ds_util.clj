(ns sched-mcp.interviewing.ds-util
  "Utilities for discovery schema"
  (:require
   [clojure.data.json :as json]
   [sched-mcp.llm :as llm]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.sutil :as sutil]
   [sched-mcp.system-db :as sdb]))

;;; Interviewing

(defn formulate-question-prompt
  "Create a vector of maps having keys #{:system :examples :context :user :format},
   a prompt for generating questions from DS + ASCR according to base-iviewr-instructions.
   The DS object contains the interview-objective, so we extract it from there."
  [{:keys [ds-instructions ascr message-history budget-remaining]
    :or {budget-remaining 0}}]
  (let [{:keys [DS interview-objective]} ds-instructions]
    (llm/build-prompt
     :system (sdb/get-agent-prompt :iviewr-formulate-question) ; This gets resources/agents/base-iviewr-instructions.md
     :user (str "Interview Objective:\n" interview-objective
                "\n\nTask Input:\n"
                (json/write-str
                 {:task-type "formulate-question"
                  :conversation-history message-history
                  :discovery-schema (sutil/clj2json-pretty DS)
                  :ASCR ascr
                  :budget budget-remaining}
                 :indent true)))))

(defn interpret-response-prompt
  "Create a prompt for interpreting response into SCR according to base-iviewr-instructions.
   The DS object contains the interview-objective, so we extract it from there.
   The LLM should look at the last entry in conversation-history to find the question/answer pair."
  [{:keys [ds-instructions message-history ascr budget-remaining]
    :or {budget-remaining 0}}]
  (let [{:keys [DS interview-objective]} ds-instructions]
    (llm/build-prompt
     :system (sdb/get-agent-prompt :iviewr-interpret-response) ; This gets resources/agents/base-iviewr-instructions.md
     :user (str "Interview Objective:\n" interview-objective
                "\n\nTask Input:\n"
                (json/write-str
                 {:task-type "interpret-response"
                  :conversation-history message-history
                  :discovery-schema (sutil/clj2json-pretty DS)
                  :ASCR ascr
                  :budget budget-remaining}
                 :indent true)))))

;;; --------------------------- The following are more generally applicable -----------------------------

(defn dispatch-ds-combine
  "Dispatch function for ds-combine - a pure function that combines SCR with ASCR"
  [tag _scr _ascr]
  (assert ((sdb/system-DS?) tag))
  tag)

(defmulti ds-combine
  "Pure function to combine a Schema-Conforming Response (SCR) with an existing
   Aggregated Schema-Conforming Response (ASCR). Returns the updated ASCR.
   This is a pure function with no side effects - no database operations."
  #'dispatch-ds-combine)

(defn dispatch-ds-complete?
  "Dispatch function for ds-complete? - a pure function that checks if an ASCR is complete"
  [tag _ascr]
  (assert ((sdb/system-DS?) tag))
  tag)

(defmulti ds-complete?
  "Pure function to check if an ASCR represents a complete Discovery Schema.
   Takes a DS tag and an ASCR, returns true if the DS is complete.
   This is a pure function with no side effects - no database operations."
  #'dispatch-ds-complete?)

(defn dispatch-ds-valid?
  [tag _pid]
  (assert ((sdb/system-DS?) tag))
  tag)

(defmulti ds-valid? #'dispatch-ds-valid?)

(defn strip-annotations
  "Transfom the Discovery Schema argument in the following ways:
     1) Replace {:val v :comment c} maps with v.
     2) Remove property :comment wherever it occurs.
     3) Remove property :invented.
   This is typically used to make s/valid?-dation easier."
  [obj]
  (cond (and (map? obj)
             (contains? obj :val)) (strip-annotations (:val obj)) ; Sometimes they won't contain :comment.
        (map? obj) (reduce-kv (fn [m k v]
                                                             ;; Sometimes interviewers think we allow comment like this; we don't!
                                (if (#{:comment :invented} k)
                                  m
                                  (assoc m k (strip-annotations v))))
                              {} obj)
        (vector? obj) (mapv strip-annotations obj)
        :else obj))

(defn collect-keys-vals
  "Collect the values of the argument property, which is a key to some nested object in the argument obj."
  [obj prop]
  (let [result (atom #{})]
    (letfn [(ck [obj]
              (cond (map? obj) (doseq [[k v] obj] (if (= k prop) (swap! result conj v) (ck v)))
                    (vector? obj) (doseq [v obj] (ck v))))]
      (ck obj))
    @result))

(defn insert-by-id
  "The argument obj has a key SOMEWHERE, prop, the value of which is a vector.
   Conj the third argument object onto that vector.
   Return the modified object."
  [obj vec-prop add-this]
  (letfn [(i-by-i [obj]
            (cond (map? obj) (reduce-kv (fn [m k v]
                                          (if (= k vec-prop)
                                            (assoc m k (conj v add-this))
                                            (assoc m k (i-by-i v))))
                                        {} obj)
                  (vector? obj) (mapv i-by-i obj)
                  :else obj))]
    (i-by-i obj)))

(defn remove-by-id
  "The argument obj has a key SOMEWHERE, vec-prop, the value of which is a vector.
   There should be in that vector an element with that has and elem-prop of value elem-id.
   Remove the object that has that elem id and return the modified top-level object, obj"
  [obj vec-prop elem-prop elem-id]
  (letfn [(r-by-i [obj]
            (cond (map? obj) (reduce-kv (fn [m k v]
                                          (if (= k vec-prop)
                                            (assoc m k (reduce (fn [rr vv]
                                                                 (if (= elem-id (get vv elem-prop))
                                                                   rr
                                                                   (conj rr vv)))
                                                               [] v))
                                            (assoc m k (r-by-i v))))
                                        {} obj)
                  (vector? obj) (mapv r-by-i obj)
                  :else obj))]
    (r-by-i obj)))

(defn replace-by-id
  [obj vec-prop elem-prop add-this]
  (if-let [elem-id (get add-this elem-prop)]
    (-> obj
        (remove-by-id vec-prop elem-prop elem-id)
        (insert-by-id vec-prop add-this))
    (throw (ex-info "Couldn't find the elem to replace."
                    {:vec-prop elem-prop :add-this add-this}))))

(defn get-object
  "The argument object obj, contains (somewhere) a key property prop having value k,
   return the object at (prop = k)."
  [obj prop kval]
  (let [found (atom nil)]
    (letfn [(somewhere [obj]
              (or @found
                  (cond (map? obj) (doseq [[k v] obj]
                                     (when (and (= k prop) (= v kval))
                                       (reset! found obj))
                                     (somewhere v))
                        (vector? obj) (doseq [x obj] (somewhere x)))))]
      (somewhere obj)
      @found)))

(defn ^:admin rebuild-ascr-from-scrs
  "Admin function to rebuild an ASCR from all SCRs for a DS.
   Useful for testing or recovery scenarios.
   Returns the rebuilt ASCR without storing it."
  [pid ds-id]
  (let [scrs (pdb/get-msg-SCR pid ds-id)]
    (reduce (fn [ascr scr]
              (ds-combine ds-id scr ascr))
            {}
            scrs)))
