(ns sched-mcp.interviewing.interview-nodes
  "Node implementations for the interview graph."
  (:require
   [clojure.stacktrace :as stacktrace]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.llm :as llm]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.util :refer [log!]]))

;;; ToDo: Could have been a macro?
(defn- safe-node-wrapper
  "Wraps node execution with comprehensive error handling and logging.
   Returns error state map on failure to allow graph to continue."
  [node-name f state]
  (try
    (log! :info (str "==> Entering node: " node-name))
    (let [istate (istate/agent-state->interview-state state)
          _ (log! :debug (str "    Input state keys: " (keys istate)))
          result (f state)
          _ (log! :info (str "<== Exiting node: " node-name " successfully"))]
      result)
    (catch Throwable e
      (log! :error (str "*** ERROR in node " node-name ": " (.getMessage e)))
      (log! :error (str "*** Exception type: " (.getName (.getClass e))))
      (log! :error (str "*** Stack trace:\n" (with-out-str (stacktrace/print-stack-trace e))))
      (let [istate (try
                     (istate/agent-state->interview-state state)
                     (catch Exception _
                       (log! :error "*** Could not convert state to InterviewState")
                       nil))]
        (when istate
          (log! :error (str "*** State at error - ds-id: " (:ds-id istate)
                            ", budget-left: " (:budget-left istate)
                            ", message count: " (count (:messages istate))
                            ", ascr keys: " (keys (:ascr istate))))))
      ;; Re-throw to stop the graph
      (throw e))))

(defn formulate-question
  "Uses the DS template and current ASCR to create contextual questions."
  [state]
  (safe-node-wrapper
   "formulate-question"
   (fn [state]
     (let [istate (istate/agent-state->interview-state state)
           {:keys [ds-id ascr messages budget-left]} istate
           message-history (->> (mapv (fn [msg]
                                        (cond
                                          (= (:from msg) :system) {:interviewer (:content msg)}
                                          (#{:human :surrogate} (:from msg)) {:expert (:content msg)}
                                          :else nil))
                                      messages)
                                (remove nil?))
           prompt (dsu/formulate-question-prompt
                   {:ds-instructions (sdb/get-DS-instructions ds-id)
                    :ascr ascr
                    :message-history message-history
                    :budget-remaining budget-left})
           result (llm/complete-json prompt :model-class :reason)
           question-text (get result :question-to-ask (:question result))]

       (log! :info (str "LangGraph formulated question for " ds-id " question-text: " question-text))
       (istate/add-message :system question-text)))
   state))

(defn get-answer-from-expert
  "Calls the surrogate with the last question from messages."
  [state]
  (safe-node-wrapper
   "get-answer-from-expert"
   (fn [state]
     (let [istate (istate/agent-state->interview-state state)
           {:keys [messages pid]} istate
           last-question (when (seq messages) (:content (last messages)))
           result (suru/surrogate-answer-question pid last-question)
           answer-text (if (:error result)
                         (str "Error: " (:error result))
                         (:response result))]
       (log! :info (str "LangGraph got answer from surrogate for " pid))
       (istate/add-message :surrogate answer-text)))
   state))

(defn interpret-response
  "Real response interpretation using LLM.
   Extracts structured SCR from the conversational answer.
   The LLM looks at the last entry in conversation-history to find the Q&A pair."
  [state]
  (safe-node-wrapper
   "interpret-response"
   (fn [state]
     (let [istate (istate/agent-state->interview-state state)
           {:keys [ds-id ascr messages budget-left]} istate
           ;; Build message history - LLM will look at the last entry for Q&A
           message-history (mapv (fn [msg]
                                   (cond
                                     (= (:from msg) :system) {:interviewer (:content msg)}
                                     (#{:human :surrogate} (:from msg)) {:expert (:content msg)}
                                     :else nil))
                                 messages)
           message-history (vec (remove nil? message-history))
           prompt (dsu/interpret-response-prompt
                   {:ds-instructions (sdb/get-DS-instructions ds-id)
                    :message-history message-history
                    :ascr ascr
                    :budget-remaining budget-left})
           result (llm/complete-json prompt :model-class :interpret)
           scr (dissoc result :iviewr-failure :ascr/budget-left :ascr/id :ascr/dstruct)] ; remove metadata from SCRs
       (if (:iviewr-failure result)
         (log! :warn (str "Interviewer reported failure: " (:iviewr-failure result)))
         (log! :info (str "LangGraph interpreted response, extracted " (count (keys scr)) " fields")))
       (istate/update-ascr scr)))
   state))

(defn check-budget
  "Decrement the question budget by the DS-specific budget-decrement (default 1.0)."
  [state]
  (safe-node-wrapper "check-budget"
                     (fn [state]
                       (let [istate (istate/agent-state->interview-state state)
                             {:keys [ds-id budget-left]} istate
                             budget-decrement (-> ds-id sdb/get-DS-instructions :budget-decrement)
                             new-budget (- budget-left budget-decrement)]
                         (log! :info (str "CHECK-BUDGET: " ds-id " | Current: " budget-left
                                          " | Decrement: " budget-decrement
                                          " | New: " new-budget))
                         (istate/decrement-budget budget-left budget-decrement)))
                     state))

(defn evaluate-completion
  "Real completion evaluation using DS-specific completion criteria.
   Uses the ds-util/ds-complete? multimethod for each DS type."
  [state]
  (safe-node-wrapper
   "evaluate-completion"
   (fn [state]
     (let [istate (istate/agent-state->interview-state state)
           {:keys [ds-id ascr budget-left]} istate
           budget-exhausted? (<= budget-left 0)
           ds-complete? (try
                          (dsu/ds-complete? ds-id ascr)
                          (catch Exception e
                            (log! :warn (str "Error checking DS completion: " (.getMessage e)))
                            false))
           complete? (or ds-complete? budget-exhausted?)]
       (log! :info (str "EVALUATE-COMPLETION: " ds-id
                        " | Budget: " budget-left
                        " | Budget exhausted? " budget-exhausted?
                        " | DS complete? " ds-complete?
                        " | Will complete? " complete?))
       (when complete?
         (log! :info (str "DS " ds-id " marked complete. Budget exhausted: " budget-exhausted?
                          ", DS criteria met: " ds-complete?)))
       (if complete?
         (istate/mark-complete)
         {:complete? false})))
   state))
