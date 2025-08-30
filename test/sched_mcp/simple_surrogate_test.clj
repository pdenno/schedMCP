(ns sched-mcp.simple-surrogate-test
  "Simple test without database dependencies"
  (:require
   [sched-mcp.llm :as llm]
   [sched-mcp.surrogate :as surrogate]
   [sched-mcp.util :refer [log!]]))

(defn test-surrogate-only
  "Test just the surrogate without database"
  []
  ;; Mock the project creation
  (let [project-id "test-project-manual"
        domain :fiberglass-garage-doors
        company-name "Premier Garage Doors"

        ;; Create persona
        persona (surrogate/create-expert-persona {:domain domain
                                                  :company-name company-name})

        ;; Initialize session
        _ (surrogate/init-expert-session project-id persona)]

    (log! :info "\n=== Testing Surrogate Without DB ===")
    (log! :info (str "Expert:" company-name))
    (log! :info (str "Domain:" domain))

    ;; Test a few questions
    (doseq [[i q] (map-indexed vector
                               ["What products do you make?"
                                "What are your main scheduling challenges?"
                                "Can you describe your production process?"
                                "What resources do you use?"])]
      (log! :info (str "\n--- Question " (inc i) " ---"))
      (log! :info (str "Q:" q))
      (let [response (surrogate/generate-expert-response project-id q)]
        (log! :info (str "A:" (:response response)))))))

(defn run-test
  []
  ;; Initialize LLM if needed
  (when-not (:api-key @llm/openai-options)
    (log! :info "Setting up LLM...")
    (llm/set-openai-key! (System/getenv "OPENAI_API_KEY")))

  (test-surrogate-only))
