(ns sched-mcp.interviewing.interview-nodes-test
  (:require
   [clojure.test]))

;;; ============================================================================
;;; Mock Nodes (Phase 2)
;;; ============================================================================

(defn mock-formulate-question
  "Mock question formulation - returns canned questions based on ASCR state.
   In Phase 3, this will call LLM with DS template and current ASCR."
  [state]
  (let [istate (istate/agent-state->interview-state state)
        ascr (:ascr istate)]
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

(defn mock-evaluate-completion
  "Mock evaluation for testing - checks for :challenges, :motivation, and :description.
   In Phase 3, this is replaced by the real evaluate-completion using ds-complete? multimethod."
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
