(ns sched-mcp.llm
  "LLM integration for Discovery Schema interviews
   Simplified version based on schedulingTBD's llm.clj"
  (:require
   [clojure.data.json :as json]
   [clojure.java.io]
   [clojure.string :as str]
   [mount.core :refer [defstate]]
   [sched-mcp.util :refer [log!]]
   [wkok.openai-clojure.api :as openai]))

;;; Configuration

(def default-provider (atom :openai))

(def model-config
  "Model configurations by provider and class"
  {:openai {:chat "gpt-4o"
            :mini "gpt-4o-mini"
            :extract "gpt-4o" ; For SCR extraction
            :reason "o1-preview"} ; For complex reasoning
   :azure {:chat "mygpt-4"}
   :meta {:chat "Llama-4-Maverick-17B-128E-Instruct-FP8"
          :mini "Llama-4-Maverick-17B-128E-Instruct-FP8"}})

;;; Credentials (from sutil)

(defn api-credentials
  "Get API credentials for provider"
  [provider]
  (case provider
    :openai {:api-key (System/getenv "OPENAI_API_KEY")}
    :azure {:api-key (System/getenv "AZURE_OPENAI_API_KEY")
            :api-endpoint "https://myopenairesourcepod.openai.azure.com"
            :impl :azure}
    :meta {:api-key (System/getenv "NIST_RCHAT")
           :api-endpoint "https://rchat.nist.gov/api"}
    (throw (ex-info "Unknown LLM provider" {:provider provider}))))

;;; Core LLM Interface

(defn pick-model
  "Get model name for class and provider"
  ([model-class] (pick-model model-class @default-provider))
  ([model-class provider]
   (or (get-in model-config [provider model-class])
       (throw (ex-info "No model configured"
                       {:provider provider :class model-class})))))

(defn query-llm
  "Given the vector of messages that is the argument, return a string (default)
   or Clojure map (:raw-text? = false) that is read from the string created by the LLM.
   This is the main function from schedulingTBD that the tests expect."
  [messages & {:keys [model-class raw-text? llm-provider response-format]
               :or {model-class :chat
                    raw-text? true
                    llm-provider @default-provider}}]
  (log! :debug (str "llm-provider = " llm-provider))
  (let [res (-> (openai/create-chat-completion
                 {:model (pick-model model-class llm-provider)
                  :response_format response-format
                  :messages messages}
                 (api-credentials llm-provider))
                :choices
                first
                :message
                :content
                (cond-> (not raw-text?) json/read-str))]
    (if (or (map? res) (string? res))
      res
      (throw (ex-info "Did not produce a map nor string." {:result res})))))

(defn complete
  "Core LLM completion function
   messages - vector of {:role :content} maps
   Options:
   - :model-class - :chat (default), :mini, :extract, :reason
   - :provider - :openai (default) or :azure
   - :temperature - 0-2, default 0.7
   - :response-format - nil or {:type 'json_object'}
   - :max-tokens - max response length

   Returns the content string from the LLM"
  [messages & {:keys [model-class provider temperature response-format max-tokens]
               :or {model-class :chat
                    provider @default-provider
                    temperature 0.7}}]
  (let [creds (api-credentials provider)
        model (pick-model model-class provider)
        params (cond-> {:model model
                        :messages messages
                        :temperature temperature}
                 response-format (assoc :response_format response-format)
                 max-tokens (assoc :max_tokens max-tokens))]
    (log! :info (str "LLM call to " model " with " (count messages) " messages"))
    (try
      (-> (openai/create-chat-completion params creds)
          :choices
          first
          :message
          :content)
      (catch Exception e
        (log! :error (str "LLM error: " (.getMessage e)))
        (throw e)))))

;;; JSON-structured responses

(defn complete-json
  "Like complete but parses JSON response
   Adds instruction to return JSON and uses json_object response format"
  [messages & opts]
  (let [messages (conj (vec (butlast messages))
                       (update (last messages) :content
                               str "\n\nReturn your response as valid JSON."))
        response (apply complete messages
                        :response-format {:type "json_object"}
                        opts)]
    (try
      (json/read-str response :key-fn keyword)
      (catch Exception e
        (log! :error (str "Failed to parse JSON: " response))
        (throw (ex-info "LLM returned invalid JSON"
                        {:response response :error e}))))))

;;; Prompt Construction Helpers

(defn system-message
  "Create a system message"
  [content]
  {:role "system" :content content})

(defn user-message
  "Create a user message"
  [content]
  {:role "user" :content content})

(defn assistant-message
  "Create an assistant message"
  [content]
  {:role "assistant" :content content})

(defn build-prompt
  "Build a complete prompt from components
   Args can include:
   - :system - system message content
   - :examples - vector of {:user :assistant} maps
   - :context - context to include before main message
   - :user - main user message
   - :format - format instructions to append"
  [& {:keys [system examples context user format]}]
  (cond-> []
    system (conj (system-message system))
    examples (into (mapcat (fn [{:keys [user assistant]}]
                             [(user-message user)
                              (assistant-message assistant)])
                           examples))
    context (conj (user-message (str "Context:\n" context)))
    user (conj (user-message
                (if format
                  (str user "\n\n" format)
                  user)))))

;;; Agent Integration
;;;  System prompts for different interviewer agents. ToDo: this could go in the system DB.
(defonce agent-prompts (atom {}))

(defn load-agent-prompt!
  "Load an agent prompt from markdown file"
  [agent-key file-path]
  (if (.exists (clojure.java.io/file file-path))
    (let [content (slurp file-path)
          ;; Extract content after the frontmatter - it's the THIRD part, not the second
          parts (str/split content #"---\n" 3)
          prompt (if (>= (count parts) 3)
                   (nth parts 2) ; Get the third part (index 2)
                   content)] ; Fallback if no frontmatter
      (swap! agent-prompts assoc agent-key prompt)
      (log! :info (str "Loaded agent prompt for " agent-key)))
    (log! :warn (str "Agent prompt file not found: " file-path))))

(defn get-agent-prompt
  "Get the system prompt for an agent"
  [agent-key]
  (or (get @agent-prompts agent-key)
      (throw (ex-info "No prompt loaded for agent" {:agent agent-key}))))

;;; Discovery Schema Prompt Templates

(defn ds-question-prompt
  "Create a prompt for generating questions from DS + ASCR according to base-iviewr-instructions.
   The DS object contains the interview-objective, so we extract it from there."
  [{:keys [ds ascr message-history budget-remaining]}]
  (let [interview-objective (:interview-objective ds)]
    (build-prompt
     :system (get-agent-prompt :generic-interviewer)
     :user (str "Interview Objective:\n" interview-objective
                "\n\nTask Input:\n"
                (json/write-str
                 {:task-type "formulate-question"
                  :conversation-history message-history
                  :discovery-schema ds
                  :ASCR ascr
                  :budget budget-remaining}
                 :indent true)))))

(defn ds-interpret-prompt
  "Create a prompt for interpreting response into SCR according to base-iviewr-instructions.
   The DS object contains the interview-objective, so we extract it from there.
   The LLM should look at the last entry in conversation-history to find the question/answer pair."
  [{:keys [ds message-history ascr budget-remaining]}]
  (let [interview-objective (:interview-objective ds)]
    (build-prompt
     :system (get-agent-prompt :generic-interviewer)
     :user (str "Interview Objective:\n" interview-objective
                "\n\nTask Input:\n"
                (json/write-str
                 {:task-type "interpret-response"
                  :conversation-history message-history
                  :discovery-schema ds
                  :ASCR ascr
                  :budget budget-remaining}
                 :indent true)))))

;;; Initialization

(defn init-llm!
  "Initialize LLM subsystem"
  []
  ;; Load agent prompts
  (load-agent-prompt! :generic-interviewer
                      "resources/agents/base-iviewr-instructions.md")
  (load-agent-prompt! :process-interviewer
                      "resources/agents/base-iviewr-instructions.md")
  (load-agent-prompt! :data-interviewer
                      "resources/agents/base-iviewr-instructions.md")
  #_(load-agent-prompt! :resources-interviewer
                        "resources/agents/resources-interviewer-agent.md")
  #_(load-agent-prompt! :optimality-interviewer
                        "resources/agents/optimality-interviewer-agent.md")
  ;; Verify credentials
  (when-not (api-credentials @default-provider)
    (throw (ex-info "No API credentials available"
                    {:provider @default-provider})))
  (log! :info "LLM subsystem initialized"))

(defstate llm-state
  :start (init-llm!))
