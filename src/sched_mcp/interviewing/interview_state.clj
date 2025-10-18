(ns sched-mcp.interviewing.interview-state
  "Interview state management using Clojure records with conversions to/from Java AgentState.

   The InterviewState record represents all state needed during an interview loop:
   - Which DS is being pursued
   - The accumulating ASCR
   - Message history
   - Budget tracking
   - Completion status"
  (:require
   [sched-mcp.interviewing.lg-util :as lgu])
  (:import [org.bsc.langgraph4j.state AgentState]))

;;; ============================================================================
;;; Interview State Record
;;; ============================================================================

(defrecord InterviewState
           [ds-id ; Keyword - :process/warm-up-with-challenges
            ascr ; Map - {} initially, builds up via reduction
            messages ; Vector - [{:from :system :content "..."} ...]
            budget-left ; Number - 10.0 initially, decrements per question
            complete? ; Boolean - false until DS completion criteria met
            pid ; Keyword - :craft-beer-123
            cid]) ; Keyword - :process

(defn make-interview-state
  "Create a new InterviewState with sensible defaults."
  [{:keys [ds-id pid cid budget-left]
    :or {budget-left 10.0}}]
  (map->InterviewState
   {:ds-id ds-id
    :ascr {}
    :messages []
    :budget-left budget-left
    :complete? false
    :pid pid
    :cid cid}))

;;; ============================================================================
;;; Schema Definition
;;; ============================================================================

(defn interview-state-schema
  "Create the channel schema for interview state.

   - :ds-id, :pid, :cid, :budget-left, :complete? use last-write
   - :ascr uses reducer to merge SCRs (converts Java maps to Clojure first)
   - :messages uses appender for message history"
  []
  {:ds-id (lgu/make-last-write-channel)
   :ascr (lgu/make-reducer-channel
          (fn [current-val new-val]
            ;; Always convert to Clojure maps before merging
            ;; (handles both Java HashMaps and Clojure maps)
            (merge (into {} (or current-val {}))
                   (into {} (or new-val {})))))
   :messages (lgu/make-appender-channel)
   :budget-left (lgu/make-last-write-channel)
   :complete? (lgu/make-last-write-channel)
   :pid (lgu/make-last-write-channel)
   :cid (lgu/make-last-write-channel)})

;;; ============================================================================
;;; Conversion Functions
;;; ============================================================================

;;; ToDo: I'm wondering why this doesn't create a Java HashMap.
(defn interview-state->map
  "Convert InterviewState record to a plain map with string keys for Java interop."
  [^InterviewState istate]
  {"ds-id" (:ds-id istate)
   "ascr" (:ascr istate)
   "messages" (:messages istate)
   "budget-left" (:budget-left istate)
   "complete?" (:complete? istate)
   "pid" (:pid istate)
   "cid" (:cid istate)})

(defn interview-state->agent-state
  "Convert InterviewState to Java AgentState for LangGraph."
  [^InterviewState istate]
  (proxy [AgentState] [(interview-state->map istate)]))

(defn agent-state->interview-state
  "Extract InterviewState from Java AgentState.
   This is the reverse conversion for working with Clojure data in nodes."
  [^AgentState state]
  (map->InterviewState
   {:ds-id (lgu/get-state-value state :ds-id)
    :ascr (or (lgu/get-state-value state :ascr) {})
    :messages (or (lgu/get-state-value state :messages) [])
    :budget-left (lgu/get-state-value state :budget-left)
    :complete? (lgu/get-state-value state :complete?)
    :pid (lgu/get-state-value state :pid)
    :cid (lgu/get-state-value state :cid)}))

;;; ============================================================================
;;; Helper Functions
;;; ============================================================================

(defn- stringify-map-keys
  "Recursively convert keyword keys to strings for Java serialization.
   Leaves string keys unchanged."
  [m]
  (cond
    (map? m) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k)
                                        (stringify-map-keys v)]) m))
    (vector? m) (mapv stringify-map-keys m)
    :else m))

(defn add-message
  "Add a message to the interview state.
   Returns update map suitable for node return value."
  [from content]
  {:messages {:from from :content content}})

(defn update-ascr
  "Update the ASCR with a new SCR (will be merged via reducer).
   Returns update map suitable for node return value."
  [scr]
  {:ascr scr})

(defn decrement-budget
  "Decrement the budget by the given amount.
   Returns update map suitable for node return value."
  [current-budget amount]
  {:budget-left (- current-budget amount)})

(defn mark-complete
  "Mark the interview as complete.
   Returns update map suitable for node return value."
  []
  {:complete? true})
