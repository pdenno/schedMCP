(ns sched-mcp.llm-mock
  "Mock LLM implementation for testing without OpenAI dependency"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [sched-mcp.util :refer [alog!]]))

;;; Mock implementations that don't require OpenAI

(def default-provider (atom :mock))

(def agent-prompts (atom {}))

(defn api-credentials [provider]
  {:api-key "mock-key"})

(defn pick-model
  ([model-class] "mock-model")
  ([model-class provider] "mock-model"))

(defn complete
  "Mock completion - returns contextual responses"
  [messages & opts]
  (let [last-msg (last messages)
        content (:content last-msg)]
    (alog! "Mock LLM call")
    (cond
      (str/includes? content "question")
      "What are the main steps in your production process?"

      (str/includes? content "extract")
      "{\"product\": \"craft beer\", \"steps\": 3}"

      :else
      "Mock response")))

(defn complete-json
  "Mock JSON completion"
  [messages & opts]
  (let [last-msg (last messages)
        content (:content last-msg)]
    (cond
      ;; Question generation
      (str/includes? content "Generate the next interview question")
      {:question "What are the main steps in your production process?"
       :help "List each step in order, including approximate duration"
       :rationale "Understanding the process flow is fundamental"
       :targets ["process-steps" "step-duration"]}

      ;; Response interpretation
      (str/includes? content "Extract structured data")
      {:scr {:product-name "craft beer"
             :process-steps ["mashing" "boiling" "fermentation" "packaging"]}
       :confidence 0.9
       :ambiguities []
       :follow_up nil}

      :else
      {:mock "response"})))

(defn system-message [content]
  {:role "system" :content content})

(defn user-message [content]
  {:role "user" :content content})

(defn assistant-message [content]
  {:role "assistant" :content content})

(defn build-prompt [& {:keys [system examples context user format]}]
  (cond-> []
    system (conj (system-message system))
    user (conj (user-message user))))

(defn load-agent-prompt! [agent-key file-path]
  (swap! agent-prompts assoc agent-key (str "Mock prompt for " agent-key))
  (alog! (str "Mock loaded agent prompt for " agent-key)))

(defn get-agent-prompt [agent-key]
  (or (get @agent-prompts agent-key)
      (str "Mock agent prompt for " agent-key)))

(defn ds-question-prompt [{:keys [ds ascr budget-remaining]}]
  [(system-message "Generate question")
   (user-message "Generate the next interview question")])

(defn ds-interpret-prompt [{:keys [ds question answer]}]
  [(system-message "Extract data")
   (user-message "Extract structured data from answer")])

(defn init-llm! []
  (load-agent-prompt! :process-interviewer "mock")
  (load-agent-prompt! :data-interviewer "mock")
  (load-agent-prompt! :resource-interviewer "mock")
  (load-agent-prompt! :optimality-interviewer "mock")
  (alog! "Mock LLM subsystem initialized"))
