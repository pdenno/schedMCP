(ns sched-mcp.llm-prompts
  (:require [clojure.string :as str]
            [sched-mcp.llm :as llm]))

;; Simplified interviewer instructions for domain-aware questioning
(defn create-domain-aware-prompt
  "Create a prompt that translates DS examples to the actual domain"
  [{:keys [ds project-name project-domain ascr budget-remaining]}]
  (let [domain-examples (case project-domain
                          "beverage" ["milling/mashing", "boiling/fermenting", "tanks", "batches"]
                          "food" ["mixing/baking", "proofing/cooling", "ovens", "batches"]
                          "manufacturing" ["cutting/shaping", "assembly/finishing", "machines", "units"]
                          ;; Default
                          ["processing", "production", "equipment", "products"])

        system-prompt (str "You are interviewing " project-name " about their " project-domain " operations.\n\n"

                           "CRITICAL: The Discovery Schema contains EXAMPLES from other industries "
                           "(pencils, glass, etc.). These are ONLY to show what TYPE of information is needed. "
                           "Translate ALL questions to their actual domain.\n\n"

                           "Their domain context:\n"
                           "- Industry: " project-domain "\n"
                           "- Key terms: " (str/join ", " domain-examples) "\n\n"

                           "Discovery Schema objective: " (:interview-objective ds) "\n\n"

                           "Information already collected:\n"
                           (if (empty? ascr)
                             "- None yet (this is the first question)\n"
                             (str/join "\n" (map (fn [[k v]] (str "- " (name k) ": " v)) ascr)))
                           "\n\nQuestions remaining: " budget-remaining "\n\n"

                           "Generate a natural, conversational question about their " project-domain
                           " operations. Never mention the example domains from the schema.")]

    [(llm/system-message system-prompt)
     (llm/user-message "What question should I ask next to understand their process better?")]))

;; Override the ds-question-prompt function
(defn ds-question-prompt
  [{:keys [ds ascr budget-remaining project-info]}]
  (create-domain-aware-prompt
   {:ds ds
    :project-name (or (:name project-info) "the company")
    :project-domain (or (:domain project-info) "manufacturing")
    :ascr ascr
    :budget-remaining budget-remaining}))
