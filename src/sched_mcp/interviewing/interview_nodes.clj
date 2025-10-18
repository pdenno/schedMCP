(ns sched-mcp.interviewing.interview-nodes
  "Node implementations for the interview graph."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.llm :as llm]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tools.orch.ds-util :as dsu]
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.util :refer [log!]]))

;;; ============================================================================
;;; Mock Nodes (Phase 2)
;;; ============================================================================

(defn mock-formulate-question
  "Mock question formulation - returns canned questions based on ASCR state.
   In Phase 3, this will call LLM with DS template and current ASCR."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        ascr (:ascr istate)
        message-count (count (:messages istate))]
    (cond
      ;; First question - ask about challenges
      (empty? ascr)
      (istate/add-message :system "What are the main scheduling challenges you face?")

      ;; Has challenges, ask about motivation
      (and (contains? ascr :challenges) (not (contains? ascr :motivation)))
      (istate/add-message :system "What is your production strategy - make-to-order or make-to-stock?")

      ;; Has both, ask for description
      (and (contains? ascr :challenges) (contains? ascr :motivation) (not (contains? ascr :description)))
      (istate/add-message :system "Can you briefly describe your scheduling process?")

      ;; Fallback
      :else
      (istate/add-message :system "Tell me more about your scheduling needs."))))

(defn mock-get-answer
  "Mock answer retrieval - returns canned answers.
   In Phase 3, this will call surrogate or get human input."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        messages (:messages istate)
        last-question (when (seq messages)
                        (:content (last messages)))]
    (cond
      (and last-question (re-find #"challenges" last-question))
      (istate/add-message :surrogate "We struggle with seasonal demand variations and equipment capacity limits.")

      (and last-question (re-find #"production strategy" last-question))
      (istate/add-message :surrogate "We primarily use make-to-stock to maintain inventory levels.")

      (and last-question (re-find #"describe your" last-question))
      (istate/add-message :surrogate "We schedule production runs based on forecasted demand and tank availability.")

      :else
      (istate/add-message :surrogate "That's an interesting question. Let me think..."))))

(defn mock-interpret-response
  "Mock response interpretation - returns canned SCRs.
   In Phase 3, this will call LLM to extract structured data from answers."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        messages (:messages istate)
        last-qa (take-last 2 messages)
        question (when (first last-qa) (:content (first last-qa)))
        _answer (when (second last-qa) (:content (second last-qa)))]
    (cond
      (and question (re-find #"challenges" question))
      (istate/update-ascr {:challenges ["Seasonal demand variations" "Equipment capacity limits"]})

      (and question (re-find #"production strategy" question))
      (istate/update-ascr {:motivation "make-to-stock"})

      (and question (re-find #"describe your" question))
      (istate/update-ascr {:description "Production scheduling based on forecasted demand and tank availability"})

      :else
      (istate/update-ascr {}))))

(defn evaluate-completion
  "Evaluate if DS completion criteria are met.
   For :process/warm-up DS, checks for :challenges, :motivation, and :description."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        ascr (:ascr istate)
        budget (:budget-left istate)

        ;; Completion criteria: has all required fields OR budget exhausted
        has-challenges? (contains? ascr :challenges)
        has-motivation? (contains? ascr :motivation)
        has-description? (contains? ascr :description)
        budget-exhausted? (<= budget 0)

        complete? (or (and has-challenges? has-motivation? has-description?)
                      budget-exhausted?)]
    (if complete?
      (istate/mark-complete)
      {:complete? false})))

(defn check-budget
  "Decrement the question budget by the DS-specific budget-decrement (default 1.0)."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        {:keys [ds-id budget-left]} istate

        ;; Get budget-decrement from DS-instructions, default to 1.0
        ds-instructions-json (sdb/get-DS-instructions-JSON ds-id)
        ds-instructions (json/read-str ds-instructions-json :key-fn keyword)
        budget-decrement (or (:budget-decrement ds-instructions) 1.0)]

    (istate/decrement-budget budget-left budget-decrement)))

;;; ============================================================================
;;; Real Nodes (Phase 3)
;;; ============================================================================

(defn formulate-question
  "Real question formulation using LLM.
   Uses the DS template and current ASCR to create contextual questions."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        {:keys [ds-id ascr messages budget-left pid cid]} istate

        ;; Get DS-instructions from system DB - includes :DS, :interview-objective, :budget-decrement
        ds-instructions-json (sdb/get-DS-instructions-JSON ds-id)
        ds-instructions (json/read-str ds-instructions-json :key-fn keyword)

        ;; Build message history in expected format
        message-history (mapv (fn [msg]
                                (cond
                                  (= (:from msg) :system)
                                  {:interviewer (:content msg)}
                                  (#{:human :surrogate} (:from msg))
                                  {:expert (:content msg)}
                                  :else nil))
                              messages)
        message-history (remove nil? message-history)

        ;; Initialize LLM if needed
        _ (when-not (seq @llm/agent-prompts)
            (llm/init-llm!))

        ;; Generate question using LLM - ds-instructions contains interview-objective
        prompt (llm/ds-question-prompt
                {:ds ds-instructions
                 :ascr ascr
                 :message-history message-history
                 :budget-remaining budget-left})
        result (llm/complete-json prompt :model-class :chat)
        question-text (get result :question-to-ask (:question result))]

    (log! :info (str "LangGraph formulated question for " ds-id))
    (istate/add-message :system question-text)))

(defn get-answer
  "Real answer retrieval from surrogate expert.
   Calls the surrogate with the last question from messages."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        {:keys [messages pid]} istate
        last-question (when (seq messages)
                        (:content (last messages)))

        ;; Call surrogate expert
        result (suru/surrogate-answer-question
                {:project-id pid
                 :question last-question})

        answer-text (if (:error result)
                      (str "Error: " (:error result))
                      (:response result))]

    (log! :info (str "LangGraph got answer from surrogate for " pid))
    (istate/add-message :surrogate answer-text)))

(defn interpret-response
  "Real response interpretation using LLM.
   Extracts structured SCR from the conversational answer.
   The LLM looks at the last entry in conversation-history to find the Q&A pair."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        {:keys [ds-id ascr messages budget-left pid cid]} istate

        ;; Get DS-instructions from system DB - includes :DS, :interview-objective, :budget-decrement
        ds-instructions-json (sdb/get-DS-instructions-JSON ds-id)
        ds-instructions (json/read-str ds-instructions-json :key-fn keyword)

        ;; Build message history - LLM will look at the last entry for Q&A
        message-history (mapv (fn [msg]
                                (cond
                                  (= (:from msg) :system)
                                  {:interviewer (:content msg)}
                                  (#{:human :surrogate} (:from msg))
                                  {:expert (:content msg)}
                                  :else nil))
                              messages)
        message-history (vec (remove nil? message-history))

        ;; Initialize LLM if needed
        _ (when-not (seq @llm/agent-prompts)
            (llm/init-llm!))

        ;; Use LLM to interpret the answer - LLM extracts Q&A from message-history
        prompt (llm/ds-interpret-prompt
                {:ds ds-instructions
                 :message-history message-history
                 :ascr ascr
                 :budget-remaining budget-left})
        result (llm/complete-json prompt :model-class :extract)

        ;; Extract SCR (remove any metadata)
        scr (dissoc result :iviewr-failure :ascr/budget-left :ascr/id :ascr/dstruct)

        ;; Check for failure
        _ (when (:iviewr-failure result)
            (log! :warn (str "Interviewer reported failure: " (:iviewr-failure result))))]

    (log! :info (str "LangGraph interpreted response, extracted " (count (keys scr)) " fields"))
    (istate/update-ascr scr)))

(defn real-evaluate-completion
  "Real completion evaluation using DS-specific completion criteria.
   Uses the ds-util/ds-complete? multimethod for each DS type."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        {:keys [ds-id ascr budget-left]} istate

        ;; Check if budget exhausted
        budget-exhausted? (<= budget-left 0)

        ;; Use the DS-specific completion check
        ds-complete? (try
                       (dsu/ds-complete? ds-id ascr)
                       (catch Exception e
                         (log! :warn (str "Error checking DS completion: " (.getMessage e)))
                         false))

        complete? (or ds-complete? budget-exhausted?)]

    (when complete?
      (log! :info (str "DS " ds-id " marked complete. Budget exhausted: " budget-exhausted?
                       ", DS criteria met: " ds-complete?)))

    (if complete?
      (istate/mark-complete)
      {:complete? false})))
