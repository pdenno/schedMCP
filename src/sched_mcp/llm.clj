(ns sched-mcp.llm
  "Utilties for using LLMs. Don't put application specific (e.g. Discovery Schema) stuff here."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io]
   [mount.core :refer [defstate]]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :refer [log!]]
   [wkok.openai-clojure.api :as openai]))

;;; Configuration

(def default-provider (atom :openai))

(def model-config
  "Model configurations by provider and class"
  {:openai {:reason "gpt-5"
            :mini "gpt-5-mini"
            :interpret "gpt-5"} ; e.g. interpret text to SCRs.
   :azure {:reason "mygpt-4"}
   :meta {:reason "Llama-4-Maverick-17B-128E-Instruct-FP8"
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
               :or {model-class :reason
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
   - :model-class - :reason (default), :mini, :extract, :reason
   - :provider - :openai (default) or :azure
   - :temperature - 0-2, default 0.7 (ignored for reasoning models like gpt-5)
   - :response-format - nil or {:type 'json_object'}
   - :max-tokens - max response length

   Returns the content string from the LLM"
  [messages & {:keys [model-class provider temperature response-format max-tokens]
               :or {model-class :reason
                    provider @default-provider
                    temperature 0.7}}]
  (let [creds (api-credentials provider)
        model (pick-model model-class provider)
        reasoning-model? (or (= model "gpt-5")
                             (= model "gpt-5-mini")
                             (.startsWith model "o1"))
        params (cond-> {:model model
                        :messages messages}
                 (and temperature (not reasoning-model?)) (assoc :temperature temperature)
                 response-format (assoc :response_format response-format)
                 max-tokens (assoc :max_tokens max-tokens))]
    (log! :info (str "LLM call to " model " with " (count messages) " messages"
                     (when reasoning-model? " (reasoning model)")))
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

;;; ToDo: Really? Can't do better than this???
(defn build-prompt
  "Build a complete prompt (a vector???) from components
   Args can include:
   - :system - system message content
   - :examples - vector of {:user :assistant} maps
   - :context - context to include before main message
   - :user - main user message
   - :format - format instructions to append.
  Values should be JSON."
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

(defn ^:admin list-openai-models
  "List id and create date of all available models."
  []
  (->> (openai/list-models (api-credentials :openai))
       :data
       (sort-by :created)
       reverse
       (mapv (fn [x] (update x :created #(-> % (* 1000) java.time.Instant/ofEpochMilli str))))))

;;; ------------------- start and stop ------------------------
(defn init-llm!
  "Initialize LLM subsystem"
  []
  ;; Load agent prompts
  (sdb/ensure-system-db!) ; ToDo: For some reason, on clj -M:test, the system-db won't have been registered.
  (sdb/store-agent-prompt! :iviewr "resources/agents/base-iviewr-instructions.md")
  (sdb/store-agent-prompt! :iviewr-formulate-question "resources/agents/interviewer-formulate.md")
  (sdb/store-agent-prompt! :iviewr-interpret-response "resources/agents/interviewer-interpret.md")
  ;; Verify credentials
  (when-not (api-credentials @default-provider)
    (throw (ex-info "No API credentials available"
                    {:provider @default-provider})))
  (log! :info "LLM subsystem initialized"))

(defstate llm-state
  :start (init-llm!))
