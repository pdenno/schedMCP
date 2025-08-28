(ns sched-mcp.llm-direct
  "Direct OpenAI API implementation without library dependency"
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sched-mcp.util :refer [log!]])
  (:import
   [java.net HttpURLConnection URL]
   [java.io OutputStreamWriter BufferedReader InputStreamReader]))

;;; Provider configuration
(def default-provider (atom :openai))

(def model-config
  "Model configurations by provider and class"
  {:openai {:chat "gpt-4o"
            :mini "gpt-4o-mini"
            :extract "gpt-4o"
            :reason "o1-preview"}
   :meta {:chat "Llama-4-Maverick-17B-128E-Instruct-FP8"
          :mini "Llama-4-Maverick-17B-128E-Instruct-FP8" ; Same model for now
          :extract "Llama-4-Maverick-17B-128E-Instruct-FP8"} ; Same model for now
   :azure {:chat "mygpt-4"}})

;;; HTTP API Implementation

(defn api-credentials [provider]
  (case provider
    :openai {:api-key (or (System/getenv "OPENAI_API_KEY")
                          (throw (ex-info "No OpenAI API key found" {})))}
    :meta {:api-key (or (System/getenv "NIST_RCHAT")
                        (throw (ex-info "No NIST RCHAT API key found" {})))}
    :azure {:api-key (or (System/getenv "AZURE_OPENAI_API_KEY")
                         (throw (ex-info "No Azure OpenAI API key found" {})))
            :api-endpoint "https://myopenairesourcepod.openai.azure.com"
            :impl :azure}
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
        ;; Different endpoint for NIST RCHAT
        endpoint (case provider
                   :meta "https://rchat.nist.gov/api/chat/completions"
                   "https://api.openai.com/v1/chat/completions")
        body (cond-> {:model model
                      :messages messages
                      :temperature temperature}
               response-format (assoc :response_format response-format)
               max-tokens (assoc :max_tokens max-tokens))
        body-str (json/write-str body)]

    (log! :info (str "LLM call to " model " with " (count messages) " messages"))

    (try
      (let [response (http-post endpoint
                                body-str
                                (:api-key creds))]
        (-> response :choices first :message :content))
      (catch Exception e
        (log! :info (str "LLM error: " (.getMessage e)) {:level :error})
        (throw e)))))

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
        (log! :info (str "Failed to parse JSON: " response) {:level :error})
        (throw (ex-info "LLM returned invalid JSON"
                        {:response response :error e}))))))

;;; Message helpers

(defn system-message [content]
  {:role "system" :content content})

(defn user-message [content]
  {:role "user" :content content})

(defn assistant-message [content]
  {:role "assistant" :content content})

;;; Prompt building

(defn build-prompt
  "Build a prompt with optional components"
  [& {:keys [system examples context user format]}]
  (cond-> []
    system (conj (system-message system))
    examples (into (mapcat (fn [{:keys [user assistant]}]
                             [(user-message user)
                              (assistant-message assistant)])
                           examples))
    context (conj (user-message (str "Context:\n" context)))
    user (conj (user-message user))
    format (conj (user-message (str "\n" format)))))

;;; Agent prompt management

(def agent-prompts (atom {}))

(defn load-agent-prompt!
  "Load an agent prompt from file"
  [agent-key file-path]
  (try
    (let [content (slurp (io/resource file-path))]
      (swap! agent-prompts assoc agent-key content)
      (log! :info (str "Loaded agent prompt for " agent-key)))
    (catch Exception e
      (log! :error (str "Agent prompt file not found: " file-path) {:level :warn}))))

(defn get-agent-prompt [agent-key]
  (or (get @agent-prompts agent-key)
      (throw (ex-info "No prompt loaded for agent"
                      {:agent agent-key}))))

;; Enhanced DS prompts with interviewer instructions

(defn load-interviewer-instructions []
  "Load the base interviewer instructions"
  (try
    (slurp (io/resource "prompts/interviewer-instructions.txt"))
    (catch Exception e
      (log! :error "Could not load interviewer instructions")
      ;; Fallback to basic instructions
      "You are an expert interviewer gathering information about manufacturing scheduling.")))

(defn prepare-ds-context
  "Prepare context with proper domain translation instructions"
  [{:keys [ds ascr project-info budget-remaining]}]
  (let [instructions (load-interviewer-instructions)
        ;; Extract just the structure, not the example values
        eads-structure (json/write-str
                        (into {}
                              (map (fn [[k v]]
                                     [k (select-keys v [:comment :type])])
                                   (:eads ds)))
                        :indent true)]
    (-> instructions
        (str/replace "{{project-domain}}" (or (:domain project-info) "manufacturing"))
        (str/replace "{{project-name}}" (or (:name project-info) "your project"))
        (str/replace "{{ds-id}}" (name (:eads-id ds)))
        (str/replace "{{interview-objective}}" (or (:interview-objective ds) ""))
        (str/replace "{{ascr-summary}}" (json/write-str ascr :indent true))
        (str/replace "{{eads-structure}}" eads-structure)
        (str/replace "{{missing-fields}}"
                     (str/join ", "
                               (remove #(contains? ascr %)
                                       (keys (:eads ds)))))
        (str/replace "{{budget-remaining}}" (str budget-remaining)))))

(defn ds-question-prompt
  [{:keys [ds ascr budget-remaining project-info] :as context}]
  (build-prompt
   :system (prepare-ds-context context)
   :user "Generate the next interview question following the guidelines above."))

(defn ds-interpret-prompt [{:keys [ds question answer]}]
  (build-prompt
   :system "You are an expert at extracting structured data from natural language while preserving nuance and context."
   :context (str "Discovery Schema structure:\n"
                 (json/write-str (:eads ds) :indent true))
   :user (str "Question asked: " question
              "\n\nUser's answer: " answer
              "\n\nExtract structured data matching the schema. Remember that any examples in the schema are just illustrations - extract based on the actual domain being discussed.")
   :format "Return JSON with:
            - scr: Object with extracted schema fields
            - confidence: 0-1 confidence score
            - ambiguities: Array of unclear items
            - follow_up: Optional clarification needed"))

;;; Initialization

(defn init-llm! []
  (load-agent-prompt! :process-interviewer "agents/process-interviewer-agent.md")
  (load-agent-prompt! :data-interviewer "agents/data-interviewer-agent.md")
  (load-agent-prompt! :resource-interviewer "agents/resource-interviewer-agent.md")
  (load-agent-prompt! :optimality-interviewer "agents/optimality-interviewer-agent.md")
  (log! :info "LLM subsystem initialized"))
