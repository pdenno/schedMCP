(ns sched-mcp.mcp-core
  "Core MCP server implementation for schedMCP using Java MCP SDK.
   Based on clojure-mcp approach for compatibility.
   Allows selection of t/r/p from clojure-mcp, as well as ours."
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [sched-mcp.config :as config]
   [sched-mcp.file-content :as file-content]
   [sched-mcp.llm] ; For mount
   [sched-mcp.project-db] ; For mount
   [sched-mcp.sutil :as sutil]
   [sched-mcp.system-db] ; For mount
   [sched-mcp.tools.iviewr.core :as iviewr]
   [sched-mcp.tools.iviewr.discovery-schema] ; For mount
   [sched-mcp.tools.iviewr-tools] ; For mount
   [sched-mcp.tools.orch.core :as orch]
   [sched-mcp.tools.surrogate :as surrogate]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.resources :as resources]
   [sched-mcp.util :as util :refer [alog! log!]])
  (:import [io.modelcontextprotocol.server.transport
            StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncResourceSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent]
           [io.modelcontextprotocol.server McpServer
            McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent
            McpSchema$Prompt
            McpSchema$PromptArgument
            McpSchema$GetPromptRequest
            McpSchema$GetPromptResult
            McpSchema$PromptMessage
            McpSchema$Role
            McpSchema$Resource
            McpSchema$ReadResourceResult
            McpSchema$TextResourceContents]
           [io.modelcontextprotocol.server McpServer
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncPromptSpecification]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(def nrepl-client-atom (atom nil))

;;; ----------------------------- Based on deprecated sched-mcp/registry.clj and clojure-mcp/main.clj
;;; ToDo: I wonder whether this is necessary or useful?!?
(def system-atom
  "for sharing state between tools (contains pid and cid)"
  (atom {}))

(def ^:diag components-atm
  "This is just for studying what gets made, but DON'T REMOVE IT."
  (atom {}))

(def ^:admin enabled-tool-category?
  "This is a configuration parameter." ; ToDo: Possible use clojure-mcp's config.clj etc.
  #{:schedmcp :cmcp-all}) ; Other possible values :cmpc-read-only :cmpc-eval :cmpc-editing :cmpc-agent

;;; Get clojure-mcp tools by category
(defn get-clojure-mcp-tools [category nrepl-client-atom]
  (try
    (require '[clojure-mcp.tools :as cmcp-tools])
    (case category
      :read-only ((resolve 'cmcp-tools/build-read-only-tools) nrepl-client-atom)
      :eval ((resolve 'cmcp-tools/build-eval-tools) nrepl-client-atom)
      :editing ((resolve 'cmcp-tools/build-editing-tools) nrepl-client-atom)
      :agent ((resolve 'cmcp-tools/build-agent-tools) nrepl-client-atom)
      :all ((resolve 'cmcp-tools/build-all-tools) nrepl-client-atom)
      [])
    (catch Exception e
      (log! :error (str "Failed to get clojure-mcp tools:" (.getMessage e)))
      [])))

(defn make-cmcp-and-smcp-tools
  "Build the complete set of tools for schedMCP"
  [nrepl-client-atom _working-dir]
  (alog! (str "Building tool registry with enabled-tool-categories: " enabled-tool-category?))
  (let [;; Always include schedMCP tools
        interviewer-tools (iviewr/create-interviewer-tools system-atom)
        orchestrator-tools (orch/create-orchestrator-tools system-atom)
        sched-tool-specs (mapv tool-system/registration-map
                               (into interviewer-tools orchestrator-tools))
        clojure-mcp-tools (if (enabled-tool-category? :cmcp-all)
                            (get-clojure-mcp-tools :all nrepl-client-atom)
                            (cond-> []
                              (enabled-tool-category? :cmcp-read-only) (into (get-clojure-mcp-tools :read-only nrepl-client-atom))
                              (enabled-tool-category? :cmcp-eval) (into (get-clojure-mcp-tools :eval nrepl-client-atom))
                              (enabled-tool-category? :cmcp-editing) (into (get-clojure-mcp-tools :editing nrepl-client-atom))
                              (enabled-tool-category? :cmcp-agent) (into (get-clojure-mcp-tools :agent nrepl-client-atom))))
        all-tools (vec (concat sched-tool-specs surrogate/tool-specs clojure-mcp-tools))]
    (log! :info (str "Total tools registered: " (count all-tools) ":\n"
                     (with-out-str (pprint (mapv :name all-tools)))))
    (swap! components-atm #(assoc % :tools all-tools))
    all-tools))

;;; ----------------------------- Based on clojure-mcp/core.clj

(defn create-mono-from-callback
  "Creates a function that takes the exchange and the arguments map and
  returns a Mono promise The callback function should take three
  arguments:
   - exchange: The MCP exchange object
   - arguments: The arguments map sent in the request
   - continuation: A function that will be called with the result and will fullfill the promise"
  [callback-fn]
  (fn [exchange arguments]
    (Mono/create
     (reify java.util.function.Consumer
       (accept [_this sink]
         (callback-fn
          exchange
          arguments
          (fn [result]
            (.success sink result))))))))

(defn- adapt-result [result]
  (cond
    (string? result) (McpSchema$TextContent. result)
    (file-content/file-response? result)
    (file-content/file-response->file-content result)
    :else (McpSchema$TextContent. " ")))

(defn ^McpSchema$CallToolResult adapt-results [list-str error?]
  (McpSchema$CallToolResult. (vec (keep adapt-result list-str)) error?))

(defn create-async-tool
  "Creates an AsyncToolSpecification with the given parameters.

   Takes a map with the following keys:
    :name         - The name of the tool
    :description  - A description of what the tool does
    :schema       - JSON schema for the tool's input parameters
    :service-atom - The atom holding the nREPL client connection.
    :tool-fn      - Function that implements the tool's logic.
                    Signature: (fn [exchange args-map nrepl-client clj-result-k] ... )
                      * exchange     - ignored (or used for advanced features)
                      * arg-map      - map with string keys representing the mcp tool call args
                      * nrepl-client - the validated and dereferenced nREPL client
                      * clj-result-k - continuation fn taking vector of strings and boolean error flag."
  [{:keys [name description schema tool-fn]}]
  (let [schema-json (json/write-str schema)
        mono-fn (create-mono-from-callback
                 (fn [exchange arg-map mono-fill-k]
                   (let [clj-result-k
                         (fn [res-list error?]
                           (mono-fill-k (adapt-results res-list error?)))]
                     (tool-fn exchange arg-map clj-result-k))))]
    (McpServerFeatures$AsyncToolSpecification.
     (McpSchema$Tool. name description schema-json)
     (reify java.util.function.BiFunction
       (apply [_this exchange arguments]
         (log! :debug (str "Args from MCP: " (pr-str arguments)))
         (mono-fn exchange arguments))))))

(defn ^McpSchema$GetPromptResult adapt-prompt-result
  "Adapts a Clojure prompt result map into an McpSchema$GetPromptResult.
   Expects a map like {:description \"...\" :messages [{:role :user :content \"...\"}]}"
  [{:keys [description messages]}]
  (let [mcp-messages (mapv (fn [{:keys [role content]}]
                             (McpSchema$PromptMessage.
                              (case role ;; Convert keyword role to McpSchema$Role enum
                                ;; :system McpSchema$Role/SYSTEM
                                :user McpSchema$Role/USER
                                :assistant McpSchema$Role/ASSISTANT
                                ;; Add other roles if needed
                                )
                              (McpSchema$TextContent. content))) ;; Assuming TextContent for now
                           messages)]
    (McpSchema$GetPromptResult. description mcp-messages)))

(defn create-async-prompt
  "Creates an AsyncPromptSpecification with the given parameters.

   Takes a map with the following keys:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument:
                   {:name \"arg-name\" :description \"...\" :required? true/false}
    :prompt-fn   - Function that implements the prompt logic.
                   Signature: (fn [exchange request-args clj-result-k] ... )
                     * exchange - The MCP exchange object
                     * request-args - Map of arguments provided in the client request
                     * clj-result-k - Continuation fn taking one map argument:
                                      {:description \"...\" :messages [{:role :user :content \"...\"}]} "
  [{:keys [name description arguments prompt-fn]}]
  (let [mcp-args (mapv (fn [{:keys [name description required?]}]
                         (McpSchema$PromptArgument. name description required?))
                       arguments)
        mcp-prompt (McpSchema$Prompt. name description mcp-args)
        mono-fn (create-mono-from-callback ;; Reuse the existing helper
                 (fn [_ request mono-fill-k]
                   ;; The request object has an .arguments() method
                   (let [request-args (.arguments ^McpSchema$GetPromptRequest request)] ;; <-- Corrected method call
                     (prompt-fn _ request-args
                                (fn [clj-result-map]
                                  (mono-fill-k (adapt-prompt-result clj-result-map)))))))]
    (McpServerFeatures$AsyncPromptSpecification.
     mcp-prompt
     (reify java.util.function.BiFunction
       (apply [_this exchange request]
         (mono-fn exchange request))))))

(defn add-tool
  "Helper function to create an async tool from a map and add it to the server."
  [mcp-server tool-map]
  (.removeTool mcp-server (:name tool-map))
  ;; Pass the service-atom along when creating the tool
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe)))

(defn create-async-resource
  "Creates an AsyncResourceSpecification with the given parameters.

   Takes a map with the following keys:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic.
                    Signature: (fn [exchange request clj-result-k] ... )
                      * exchange     - The MCP exchange object
                      * request      - The request object
                      * clj-result-k - continuation fn taking a vector of strings"
  [{:keys [url name description mime-type resource-fn]}]
  (let [resource (McpSchema$Resource. url name description mime-type nil)
        mono-fn (create-mono-from-callback
                 (fn [exchange request mono-fill-k]
                   (resource-fn
                    exchange
                    request
                    (fn [result-strings]
                      ;; Create TextResourceContents objects with the URL and MIME type
                      (let [resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                                    result-strings)]
                        ;; Create ReadResourceResult with the list of TextResourceContents
                        (mono-fill-k (McpSchema$ReadResourceResult. resource-contents)))))))]
    (McpServerFeatures$AsyncResourceSpecification.
     resource
     (reify java.util.function.BiFunction
       (apply [_this exchange request]
         (mono-fn exchange request))))))

(defn add-resource
  "Helper function to create an async resource from a map and add it to the server.

   Takes an MCP server and a resource map with:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic."
  [mcp-server resource-map]
  (.removeResource mcp-server (:url resource-map))
  (-> (.addResource mcp-server (create-async-resource resource-map))
      (.subscribe)))

(defn add-prompt
  "Helper function to create an async prompt from a map and add it to the server.

   Takes an MCP server and a prompt map with:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument
    :prompt-fn   - Function that implements the prompt logic."
  [mcp-server prompt-map]
  (.removePrompt mcp-server (:name prompt-map))
  (-> (.addPrompt mcp-server (create-async-prompt prompt-map))
      (.subscribe)))

;; helper tool to demonstrate how all this gets hooked together

(defn mcp-server
  "Creates a basic stdio mcp server"
  []
  (log! :info "Starting MCP server")
  (try
    (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
          server (-> (McpServer/async transport-provider)
                     (.serverInfo "schedMCP" "0.1.0")
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.prompts true)
                                        (.resources true true) ;; resources method takes two boolean parameters
                                        #_(.logging)
                                        (.build)))
                     (.build))]

      (log! :info "MCP server initialized successfully")
      server)
    (catch Exception e
      (log! :error (str "Failed to initialize MCP server: " e))
      (throw e))))

(defn mcp-server
  "Creates a basic stdio mcp server"
  []
  (log! :info "Starting MCP server")
  (try
    (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
          server (-> (McpServer/async transport-provider)
                     (.serverInfo "schedMCP" "0.1.0")
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.prompts true)
                                        (.resources true true) ;; resources method takes two boolean parameters
                                        #_(.logging)
                                        (.build)))
                     (.build))]

      (log! :info "MCP server initialized successfully")
      server)
    (catch Exception e
      (log! :error (str "Failed to initialize MCP server: " e))
      (throw e))))


;;; (defn load-config-handling-validation-errors [config-file user-dir]
;;;   (try
;;;     (config/load-config config-file user-dir)
;;;     (catch Exception e
;;;       (if (= ::config/schema-error (-> e ex-data :type))
;;;         (let [{:keys [errors file-path]} (ex-data e)]
;;;           (binding [*out* *err*]
;;;             (println (str "\n❌ Configuration validation failed!\n"))
;;;             (when file-path
;;;               (println (str "File: " file-path "\n")))
;;;             (println "Errors found:")
;;;             (doseq [[k v] errors]
;;;               (let [msg (if (sequential? v) (first v) v)]
;;;                 (println (str " 👉 " k " - " msg))))
;;;             (println "\nPlease fix these issues and try again.")
;;;             (println "See CONFIG.md for documentation.\n"))
;;;           (throw e))
;;;         ;; Other error - re-throw
;;;         (throw e)))))
;;;
;;; (defn fetch-config [nrepl-client-map config-file cli-env-type env-type project-dir]
;;;   (let [user-dir (dialects/fetch-project-directory nrepl-client-map env-type project-dir)]
;;;     (when-not user-dir
;;;       (log/warn "Could not determine working directory")
;;;       (throw (ex-info "No project directory!!" {})))
;;;     (log/info "Working directory set to:" user-dir)
;;;
;;;     (let [config (load-config-handling-validation-errors config-file user-dir)
;;;           final-env-type (or cli-env-type
;;;                              (if (contains? config :nrepl-env-type)
;;;                                (:nrepl-env-type config)
;;;                                env-type))]
;;;        (assoc nrepl-client-map ::config/config (assoc config :nrepl-env-type final-env-type)))))

(defn create-and-start-nrepl-connection
  "Convenience higher-level API function to create and initialize an nREPL connection.

   This function handles the complete setup process including:
   - Creating the nREPL client connection
   - Starting the polling mechanism
   - Loading required namespaces and helpers (if Clojure environment)
   - Setting up the working directory
   - Loading configuration

   Takes initial-config map with :port and optional :host, :project-dir, :nrepl-env-type, :config-file.
   Returns the configured nrepl-client-map with ::config/config attached."
  [{:keys [_project-dir _config-file] :as initial-config}]
  (log! :info (str "Creating nREPL connection with config: " initial-config))
  (try
    (require '[clojure-mcp.nrepl :as nrepl])
    (let [nrepl-client-map ((resolve 'nrepl/create) (dissoc initial-config :project-dir :nrepl-env-type))
          #_#_cli-env-type (:nrepl-env-type initial-config)
          _ (do
              (log! :info "nREPL client map created")
              ((resolve 'nrepl/start-polling) nrepl-client-map)
              (log! :info "Started polling nREPL"))]
          ;; Detect environment type early
          ;; TODO this needs to be sorted out
;;;          env-type (dialects/detect-nrepl-env-type nrepl-client-map)
;;;          nrepl-client-map-with-config (fetch-config nrepl-client-map
;;;                                                     config-file
;;;                                                     cli-env-type
;;;                                                     env-type
;;;                                                     project-dir)
;;;          nrepl-env-type' (config/get-config nrepl-client-map-with-config :nrepl-env-type)]
      (log! :debug "Initializing Clojure environment")
;;;      (dialects/initialize-environment nrepl-client-map-with-config nrepl-env-type')
;;;      (dialects/load-repl-helpers nrepl-client-map-with-config nrepl-env-type')
      (log! :debug "Environment initialized")
      nrepl-client-map #_nrepl-client-map-with-config)
    (catch Exception e
      (log! :error (str "Failed to create nREPL connection: " e))
      (throw e))))

(defn close-servers
  "Convenience higher-level API function to gracefully shut down MCP and nREPL servers.

   This function handles the complete shutdown process including:
   - Stopping nREPL polling if a client exists in nrepl-client-atom
   - Gracefully closing the MCP server
   - Proper error handling and logging"
  [nrepl-client-atom]
  (log! :info "Shutting down servers")
  (try
    (require '[clojure-mcp.nrepl :as nrepl])
    (require '[clojure-mcp.nrepl-launcher :as nrepl-launcher])
    (when-let [client @nrepl-client-atom]
      (log! :info "Stopping nREPL polling")
      ((resolve 'nrepl/stop-polling) client)
      ;; Clean up auto-started nREPL process if present
      (when-let [nrepl-process (:nrepl-process client)]
        (log! :info "Cleaning up auto-started nREPL process")
        ((resolve 'nrepl-launcher/destroy-nrepl-process) nrepl-process))
      (when-let [mcp-server (:mcp-server client)]
        (log! :info "Closing MCP server gracefully")
        (.closeGracefully mcp-server)
        (log! :info "Servers shut down successfully")))
    (catch Exception e
      (log! :error (str "Error during server shutdown: " e))
      (throw e))))

(s/def ::port pos-int?)
(s/def ::host string?)
(s/def ::nrepl-env-type keyword?)
(s/def ::project-dir (s/and string?
                            #(try (let [f (io/file %)]
                                    (and (.exists f) (.isDirectory f)))
                                  (catch Exception _ false))))
(s/def ::config-file (s/and string?
                            #(try (let [f (io/file %)]
                                    (and (.exists f) (.isFile f)))
                                  (catch Exception _ false))))
(s/def ::start-nrepl-cmd (s/coll-of string? :kind vector?))
(s/def ::nrepl-args (s/keys :req-un []
                            :opt-un [::port ::host ::config-file ::project-dir ::nrepl-env-type
                                     ::start-nrepl-cmd]))

(defn coerce-options [{:keys [project-dir config-file] :as opts}]
  (cond-> opts
    (symbol? project-dir)
    (assoc :project-dir (str project-dir))
    (symbol? config-file)
    (assoc :config-file (str config-file))))

(defn validate-options
  "Validates the options map for build-and-start-mcp-server.
   Throws an exception with spec explanation if validation fails."
  [opts]
  (let [opts (coerce-options opts)]
    (if-not (s/valid? ::nrepl-args opts)
      (let [explanation (s/explain-str ::nrepl-args opts)]
        (println "Invalid options:" explanation)
        (log! :error (str "Invalid options: " explanation))
        (throw (ex-info "Invalid options for MCP server"
                        {:explanation explanation
                         :spec-data (s/explain-data ::nrepl-args opts)})))
      opts)))

(defn ensure-port
  "Ensures the args map contains a :port key.
   Throws an exception with helpful context if port is missing.

   Args:
   - args: Map that should contain :port

   Returns: args unchanged if :port exists

   Throws: ExceptionInfo if :port is missing"
  [args]
  (if (:port args)
    args
    (throw
     (ex-info
      "No nREPL port available - either provide :port or configure auto-start"
      {:provided-args args}))))

(defn register-components
  "Registers tools, prompts, and resources with the MCP server, applying config-based filtering.

   Args:
   - mcp-server: The MCP server instance to add components to
   - nrepl-client-map: The nREPL client map containing config
   - components: Map with :tools, :prompts, and :resources sequences

   Side effects:
   - Adds enabled components to the MCP server
   - Logs debug messages for enabled components

   Returns: nil"
  [mcp-server _nrepl-client-map {:keys [tools prompts resources]}]

  ;; Register tools with filtering
  (doseq [tool tools]
    (when true ; (config/tool-id-enabled? nrepl-client-map (:id tool))
      (log! :debug (str "Enabling tool: " (:id tool)))
      (add-tool mcp-server tool)))

  ;; Register resources with filtering
  (doseq [resource resources]
    (when true ; (config/resource-name-enabled? nrepl-client-map (:name resource))
      (log! :debug (str "Enabling resource: " (:name resource)))
      (add-resource mcp-server resource)))

  ;; Register prompts with filtering
  (doseq [prompt prompts]
    (when true ; (config/prompt-name-enabled? nrepl-client-map (:name prompt))
      (log! :debug (str "Enabling prompt: " (:name prompt)))
      (add-prompt mcp-server prompt)))
  nil)

(defn build-components
  "Builds tools, prompts, and resources using the provided factory functions.

   Args:
   - nrepl-client-atom: Atom containing the nREPL client
   - working-dir: Working directory path
   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources

   Returns: Map with :tools, :prompts, and :resources sequences"
  [nrepl-client-atom working-dir {:keys [make-tools-fn
                                         make-prompts-fn
                                         make-resources-fn]
                                  :as _component-factories}]
  {:tools (when make-tools-fn
            (doall (make-tools-fn nrepl-client-atom working-dir)))
   :prompts (when make-prompts-fn
              (doall (make-prompts-fn nrepl-client-atom working-dir)))
   :resources (when make-resources-fn
                (doall (make-resources-fn nrepl-client-atom working-dir)))})

(defn setup-mcp-server
  "Sets up an MCP server by building components, creating the server, and registering components.

   This function encapsulates the common pattern used by both stdio and SSE transports:
   1. Build components using factory functions
   2. Create the MCP server (transport-specific)
   3. Register components with filtering

   Args:
   - nrepl-client-atom: Atom containing the nREPL client map
   - working-dir: Working directory path
   - component-factories: Map with factory functions (:make-tools-fn, :make-prompts-fn, :make-resources-fn)
   - server-thunk: Zero-argument function that creates and returns a map with :mcp-server

   The server-thunk is called AFTER components are built but BEFORE they are registered,
   ensuring components are ready for immediate registration once the server starts.

   Returns: The result map from server-thunk (containing at least :mcp-server)"
  [nrepl-client-atom working-dir component-factories server-thunk]
  ;; Build components first to minimize latency
  (let [components (build-components nrepl-client-atom working-dir component-factories)
        ;; Create server after components are ready
        server-result (server-thunk)
        mcp-server (:mcp-server server-result)]
    ;; Register components with filtering
    (register-components mcp-server @nrepl-client-atom components)
    server-result))

(defn build-and-start-mcp-server-impl
  "Internal implementation of MCP server startup.

   Builds and starts an MCP server with the provided configuration.

   This is the main entry point for creating custom MCP servers. It handles:
   - Validating input options
   - Creating and starting the nREPL connection
   - Setting up the working directory
   - Calling factory functions to create tools, prompts, and resources
   - Registering everything with the MCP server

   Args:
   - nrepl-args: Map with connection settings
     - :port (required) - nREPL server port
     - :host (optional) - nREPL server host (defaults to localhost)
     - :project-dir (optional) - Root directory for the project (must exist)

   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources

   All factory functions are optional. If not provided, that category won't be populated.

   Side effects:
   - Stores the nREPL client in core/nrepl-client-atom
   - Starts the MCP server on stdio

   Returns: nil"
  [nrepl-args component-factories]
  (let [_ (assert (:port nrepl-args) "Port must be provided for build-and-start-mcp-server-impl")
        nrepl-client-map (create-and-start-nrepl-connection nrepl-args)
        working-dir (or (:project-dir nrepl-args)
                        (throw (ex-info "Could not find working-dir on nrepl-args." {})))
        smcp-config-path (io/file working-dir "config.edn")
        smcp-config-map (if (.exists smcp-config-path) (-> smcp-config-path slurp edn/read-string) {})

        ;; Load and validate config from ./config.edn and ${HOME}/.clojure-mcp/config.edn.
        loaded-config (config/load-and-validate-configs working-dir) ; <============================ Get it from clojure-mcp!

        ;; Store nREPL process (if auto-started) in client map for cleanup
        nrepl-client-with-process (if-let [process (:nrepl-process nrepl-args)]
                                    (assoc nrepl-client-map :nrepl-process process)
                                    nrepl-client-map)]
    (reset! nrepl-client-atom nrepl-client-with-process)
    ;; Store the loaded config under the sched-mcp namespace key (not clojure-mcp)
    (swap! nrepl-client-atom #(assoc % :clojure-mcp.config/config loaded-config))
    (swap! nrepl-client-atom #(assoc % :sched-mcp.config/config smcp-config-map))

    ;; Setup MCP server with stdio transport
    (let [server-result (setup-mcp-server nrepl-client-atom
                                          working-dir
                                          component-factories
                                          ;; stdio server creation thunk returns map
                                          (fn [] {:mcp-server (mcp-server)}))
          mcp (:mcp-server server-result)]
      (swap! nrepl-client-atom assoc :mcp-server mcp)
    nil)))

(defn build-and-start-mcp-server
  "Builds and starts an MCP server with optional automatic nREPL startup.

   This function wraps build-and-start-mcp-server-impl with nREPL auto-start capability.

   If auto-start conditions are met (see nrepl-launcher/should-start-nrepl?), it will:
   1. Start an nREPL server process using :start-nrepl-cmd
   2. Parse the port from process output (if no :port provided)
   3. Pass the discovered port to the main MCP server setup

   Otherwise, it requires a :port parameter.

   Args:
   - nrepl-args: Map with connection settings and optional nREPL start
     configuration
     - :port (required if not auto-starting) - nREPL server port
       When provided with :start-nrepl-cmd, uses fixed port instead of parsing
     - :host (optional) - nREPL server host (defaults to localhost)
     - :project-dir (optional) - Root directory for the project
     - :start-nrepl-cmd (optional) - Command to start nREPL server

   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources

   Auto-start conditions (must satisfy ONE):
   1. Both :start-nrepl-cmd AND :project-dir provided in nrepl-args
   2. Current directory contains .clojure-mcp/config.edn with :start-nrepl-cmd

   Returns: nil"
  [nrepl-args component-factories]
  (require '[clojure-mcp.nrepl-launcher :as nrepl-launcher])
  (-> nrepl-args
      validate-options
      ((resolve 'nrepl-launcher/maybe-start-nrepl-process))
      ensure-port
      (build-and-start-mcp-server-impl component-factories)))

;;; ------- Based on clojure-mcp/main.clj and sched-mcp/registry.clj --------------------------------
;; Delegate to prompts namespace
;; Note: working-dir param kept for compatibility with core API but unused
(defn make-prompts [nrepl-client-atom _working-dir]
  (require '[clojure-mcp.prompts :as prompts])
  (let [prompts ((resolve 'prompts/make-prompts) nrepl-client-atom)]
    (log! :info (str "Total prompts registered: " (count prompts) ":\n"
                     (with-out-str (pprint (mapv :name prompts)))))
    (swap! components-atm #(assoc % :prompts prompts))
    prompts))

;; Delegate to resources namespace
;; Note: working-dir param kept for compatibility with core API but unused. (I think it should be in nrepl-client-atom ???)
(defn make-resources
  "Create schedMCP-specific resources.
   Delegates to sched-mcp.resources namespace."
  [nrepl-client-atom _working-dir]
  (let [resources (resources/make-resources nrepl-client-atom)]
    (log! :info (str "Total resources registered: " (count resources) ":\n"
                     (with-out-str (pprint (mapv :name resources)))))
    (swap! components-atm #(assoc % :resources resources))
    resources))

;;; ------------------------------ Starting and stopping -------------------------

;;; CD suggested this one.
#_(defn ^:diag reload-components
  "Reload configuration and re-register components without stopping the server.
   For development use only."
  []
  (let [working-dir "/home/pdenno/Documents/git/schedMCP"
        loaded-config (load-and-validate-config working-dir)
        _ (swap! nrepl-client-atom assoc :sched-mcp.config/config loaded-config)
        mcp-server (:mcp-server @nrepl-client-atom)
        components (build-components nrepl-client-atom
                                    working-dir
                                    {:make-tools-fn make-cmcp-and-smcp-tools
                                     :make-prompts-fn make-prompts
                                     :make-resources-fn make-resources})]
    (register-components mcp-server @nrepl-client-atom components)
    :reloaded))

;;; I'm thinking something like this might work. It doesn't touch the configs though (good thing? bad thing?).
;;; Currently it generates errors: SLF4J/ERROR  : - Operator called default onErrorDropped
(defn ^:diag reload-components!
  "Reload configuration and re-register components without stopping the server.
   Probably for development use only."
  []
  (let [working-dir (as-> "(System/getProperty \"user.dir\")" ?x
                      ((resolve 'clojure-mcp.tools.eval.core/evaluate-code) @nrepl-client-atom {:code ?x})
                      (get-in ?x [:outputs 0 1])
                      (edn/read-string ?x))
        components (build-components nrepl-client-atom
                                     working-dir
                                     {:make-tools-fn make-cmcp-and-smcp-tools
                                      :make-prompts-fn make-prompts
                                      :make-resources-fn make-resources})
        mcp-server (:mcp-server @nrepl-client-atom)]
    (register-components mcp-server @nrepl-client-atom components)))

(def server-promise
  "This is used in main to keep the server from exiting immediately."
  (p/deferred))

(defn start-mcp-server []
  (try
    (when-not @sutil/nrepl-server (sutil/start-nrepl-server))
    (build-and-start-mcp-server
     {:port 7888
      :project-dir "/home/pdenno/Documents/git/schedMCP"} ; See build-and-start-mcp-server above.
     {:make-tools-fn make-cmcp-and-smcp-tools
      :make-prompts-fn make-prompts
      :make-resources-fn make-resources})
    (catch Exception e
      (log! :error (str "Server exception: " e))
      (p/resolve! server-promise :failed-to-start))))

(defn stop-mcp-server
  "Stop the MCP server"
  []
  (log! :info "Stopping schedMCP server...")
  (close-servers nrepl-client-atom)
  (p/resolve! server-promise :exited))

;;; Starting and stopping
(defstate mcp-core-server
  :start (start-mcp-server)
  :stop (stop-mcp-server))
