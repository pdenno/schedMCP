(ns sched-mcp.llm-direct
  "Direct OpenAI API implementation without library dependency"
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sched-mcp.util :refer [alog!]])
  (:import
   [java.net HttpURLConnection URL]
   [java.io OutputStreamWriter BufferedReader InputStreamReader]))

;;; Configuration

(def default-provider (atom :openai))

(def model-config
  "Model configurations by provider and class"
  {:openai {:chat "gpt-4o"
            :mini "gpt-4o-mini"
            :extract "gpt-4o"
            :reason "o1-preview"}})

;;; HTTP API Implementation

(defn api-credentials
  "Get API credentials for provider"
  [provider]
  (case provider
    :openai {:api-key (System/getenv "OPENAI_API_KEY")}
    (throw (ex-info "Unknown LLM provider" {:provider provider}))))

(defn pick-model
  "Get model name for class and provider"
  ([model-class] (pick-model model-class @default-provider))
  ([model-class provider]
   (or (get-in model-config [provider model-class])
       (throw (ex-info "No model configured"
                       {:provider provider :class model-class})))))

(defn http-post
  "Make HTTP POST request to OpenAI API"
  [endpoint body api-key]
  (let [url (URL. endpoint)
        conn (.openConnection url)]
    (.setRequestMethod conn "POST")
    (.setRequestProperty conn "Content-Type" "application/json")
    (.setRequestProperty conn "Authorization" (str "Bearer " api-key))
    (.setDoOutput conn true)

    ;; Write request
    (with-open [writer (OutputStreamWriter. (.getOutputStream conn))]
      (.write writer body))

    ;; Read response
    (let [response-code (.getResponseCode conn)]
      (if (>= response-code 400)
        (throw (ex-info "API request failed"
                        {:status response-code
                         :error (slurp (.getErrorStream conn))}))
        (with-open [reader (BufferedReader. (InputStreamReader. (.getInputStream conn)))]
          (json/read-str (slurp reader) :key-fn keyword))))))

(defn complete
  "Core LLM completion function using direct HTTP API"
  [messages & {:keys [model-class provider temperature response-format max-tokens]
               :or {model-class :chat
                    provider @default-provider
                    temperature 0.7}}]
  (let [creds (api-credentials provider)
        model (pick-model model-class provider)
        body (cond-> {:model model
                      :messages messages
                      :temperature temperature}
               response-format (assoc :response_format response-format)
               max-tokens (assoc :max_tokens max-tokens))
        body-str (json/write-str body)]

    (alog! (str "LLM call to " model " with " (count messages) " messages"))

    (try
      (let [response (http-post "https://api.openai.com/v1/chat/completions"
                                body-str
                                (:api-key creds))]
        (-> response :choices first :message :content))
      (catch Exception e
        (alog! (str "LLM error: " (.getMessage e)) {:level :error})
        (throw e)))))

;;; JSON-structured responses

(defn complete-json
  "Like complete but parses JSON response"
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
        (alog! (str "Failed to parse JSON: " response) {:level :error})
        (throw (ex-info "LLM returned invalid JSON"
                        {:response response :error e}))))))

;;; Re-export the same interface as sched-mcp.llm

(defn system-message [content]
  {:role "system" :content content})

(defn user-message [content]
  {:role "user" :content content})

(defn assistant-message [content]
  {:role "assistant" :content content})

(defn build-prompt [& {:keys [system examples context user format]}]
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

(def agent-prompts (atom {}))

(defn load-agent-prompt! [agent-key file-path]
  (if (.exists (io/file file-path))
    (let [content (slurp file-path)
          prompt (second (str/split content #"---\n" 3))]
      (swap! agent-prompts assoc agent-key prompt)
      (alog! (str "Loaded agent prompt for " agent-key)))
    (alog! (str "Agent prompt file not found: " file-path) {:level :warn})))

(defn get-agent-prompt [agent-key]
  (or (get @agent-prompts agent-key)
      (throw (ex-info "No prompt loaded for agent" {:agent agent-key}))))

(defn ds-question-prompt [{:keys [ds ascr _message-history budget-remaining]}]
  (build-prompt
   :system (get-agent-prompt :process-interviewer)
   :context (str "Discovery Schema:\n"
                 (json/write-str (:eads ds) :indent true)
                 "\n\nCurrent ASCR:\n"
                 (json/write-str ascr :indent true)
                 "\n\nBudget remaining: " budget-remaining " questions")
   :user "Generate the next interview question to gather missing information."
   :format "Return JSON with fields:
            - question: The natural language question
            - help: Additional context or examples
            - rationale: Why this question now
            - targets: Array of DS fields this aims to fill"))

(defn ds-interpret-prompt [{:keys [ds question answer]}]
  (build-prompt
   :system "You are an expert at extracting structured data from natural language."
   :context (str "Discovery Schema structure:\n"
                 (json/write-str (:eads ds) :indent true))
   :user (str "Question asked: " question
              "\n\nUser's answer: " answer
              "\n\nExtract structured data matching the schema.")
   :format "Return JSON with:
            - scr: Object with extracted schema fields
            - confidence: 0-1 confidence score
            - ambiguities: Array of unclear items
            - follow_up: Optional clarification needed"))

(defn init-llm! []
  (load-agent-prompt! :process-interviewer "docs/agents/process-interviewer-agent.md")
  (load-agent-prompt! :data-interviewer "docs/agents/data-interviewer-agent.md")
  (load-agent-prompt! :resource-interviewer "docs/agents/resource-interviewer-agent.md")
  (load-agent-prompt! :optimality-interviewer "docs/agents/optimality-interviewer-agent.md")
  (when-not (api-credentials @default-provider)
    (throw (ex-info "No API credentials available" {:provider @default-provider})))
  (alog! "LLM subsystem initialized"))
