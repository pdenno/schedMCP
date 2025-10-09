(ns sched-mcp.mcp-core
  "Core MCP server implementation for schedMCP using Java MCP SDK.
   Based on clojure-mcp approach for compatibility.
   Allows selection of t/r/p from clojure-mcp, as well as ours."
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.pprint :refer [pprint]]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [sched-mcp.file-content :as file-content]
   [sched-mcp.llm] ; For mount
   [sched-mcp.nrepl] ; For mount
   [sched-mcp.project-db] ; For mount
   [sched-mcp.prompts :as prompts]
   [sched-mcp.system-db] ; For mount
   [sched-mcp.tools.iviewr.core :as iviewr]
   [sched-mcp.tools.iviewr-tools] ; For mount
   [sched-mcp.tools.orch.core :as orch]
   [sched-mcp.tools.surrogate.core :as sur] ; For mount
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.resources :as resources]
   [sched-mcp.util :as util :refer [log!]])
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

(def ^:diag mcp-server-atm
  "Might just be for diagnostics."
  (atom nil))

;;; ----------------------------- Based on deprecated sched-mcp/registry.clj and clojure-mcp/main.clj
;;; ToDo: I wonder whether this is necessary or useful?!?

(def ^:diag components-atm
  "This is just for studying what gets made, but DON'T REMOVE IT."
  (atom {}))

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
    :tool-fn      - Function that implements the tool's logic.
                    Signature: (fn [exchange args-map clj-result-k] ... )
                      * exchange     - ignored (or used for advanced features)
                      * arg-map      - map with string keys representing the mcp tool call args
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

;;; Claude Code: When you have 26 tools + 8 prompts + 5 resources, that's 39 notifications trying to be sent during initialization, overwhelming the STDIO transport before it's fully ready.
;;; The fix is to remove the .subscribe() calls - the MCP SDK handles subscriptions internally. Here's the fix:

(defn add-tool
  "Helper function to create an async tool from a map and add it to the server."
  [mcp-server tool-map]
  (.removeTool mcp-server (:name tool-map))
  ;; Pass the service-atom along when creating the tool
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe))) ; Claude Code suggests overwhelmed by this. (See above.)

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

(defn close-mcp-server!
  "Convenience higher-level API function to gracefully shut down the MCP server.

   This function handles the complete shutdown process including:
   - Gracefully closing the MCP server
   - Proper error handling and logging"
  []
  (log! :info "Shutting down servers")
  (try
    (when-let [mcp-server @mcp-server-atm]
      (log! :info "Closing MCP server gracefully")
      (.closeGracefully mcp-server)
      (log! :info "Servers shut down successfully")
      (reset! mcp-server-atm nil))
    (catch Exception e
      (log! :error (str "Error during server shutdown: " e))
      (throw (ex-info "error during shutdown" {:msg (.getMessae e)})))))

(defn register-components!
  "Registers tools, prompts, and resources with the MCP server, applying config-based filtering.

   Args:
   - mcp-server: The MCP server instance to add components to
   - components: Map with :tools, :prompts, and :resources sequences

   Side effects:
   - Adds enabled components to the MCP server
   - Logs debug messages for enabled components

   Returns: nil"
  [mcp-server {:keys [tools prompts resources]}]

  ;; Register tools with filtering
  (doseq [tool tools]
    (log! :debug (str "Adding tool: " (:id tool)))
    (add-tool mcp-server tool))

  ;; Register resources with filtering
  (doseq [resource resources]
    (log! :debug (str "Adding resource: " (:name resource)))
    (add-resource mcp-server resource))

  ;; Register prompts with filtering
  (doseq [prompt prompts]
    (log! :debug (str "Adding prompt: " (:name prompt)))
    (add-prompt mcp-server prompt))
  nil)

(defn build-components
  "Builds tools, prompts, and resources using the provided factory functions.

   Args:
   - config-map: Working directory path
   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [config-map] ...) returns seq of tools
     - :make-prompts-fn - (fn [config-map] ...) returns seq of prompts
     - :make-resources-fn - (fn [config-map] ...) returns seq of resources

   Returns: Map with :tools, :prompts, and :resources sequences"
  [{:keys [make-tools-fn make-prompts-fn make-resources-fn] :as _component-factories}]
  (let [config (or (not-empty (-> "config.edn" slurp edn/read-string)) {})]
    {:tools (when make-tools-fn
              (doall (make-tools-fn config)))
     :prompts (when make-prompts-fn
                (doall (make-prompts-fn config)))
     :resources (when make-resources-fn
                  (doall (make-resources-fn config)))}))

(defn setup-mcp-server
  "Sets up an MCP server by building components, creating the server, and registering components.

   This function encapsulates the common pattern used by both stdio and SSE transports:
   1. Build components using factory functions
   2. Create the MCP server (transport-specific)
   3. Register components with filtering

   Args:
   - config-map: Working directory path
   - component-factories: Map with factory functions (:make-tools-fn, :make-prompts-fn, :make-resources-fn)
   - server-thunk: Zero-argument function that creates and returns a map with :mcp-server

   The server-thunk is called AFTER components are built but BEFORE they are registered,
   ensuring components are ready for immediate registration once the server starts.

   Returns: The result map from server-thunk (containing at least :mcp-server)"
  [component-factories server-thunk]
  ;; Build components first to minimize latency
  (let [components (build-components component-factories)
        ;; Create server after components are ready
        server-result (server-thunk)
        mcp-server (:mcp-server server-result)]
    ;; Register components with filtering
    (reset! mcp-server-atm mcp-server)
    (register-components! mcp-server components)
    server-result))

(defn build-and-start-mcp-server
  "Internal implementation of MCP server startup.

   Builds and starts an MCP server with the provided configuration.

   This is the main entry point for creating custom MCP servers. It handles:
   - Validating input options
   - Setting up the working directory
   - Calling factory functions to create tools, prompts, and resources
   - Registering everything with the MCP server

   Args:
   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [config-map] ...) returns seq of tools
     - :make-prompts-fn - (fn [config-map] ...) returns seq of prompts
     - :make-resources-fn - (fn [config-map] ...) returns seq of resources

   All factory functions are optional. If not provided, that category won't be populated.

   Side effects:
   - Starts the MCP server on stdio

   Returns: mcp server"
  [component-factories]
  (let [server-result (setup-mcp-server component-factories
                                        ;; stdio server creation thunk returns map
                                        (fn [] {:mcp-server (mcp-server)}))]
    (:mcp-server server-result)))

(defn make-tools!
  "Build the complete set of tools for schedMCP"
  [_config-map]
  (let [iviewr-tools (iviewr/create-iviewr-tools)
        orch-tools (orch/create-orch-tools)
        sur-tools (sur/create-sur-tools)
        all-tools (mapv tool-system/registration-map
                        (into (into iviewr-tools orch-tools) sur-tools))]
    (log! :info (str "Total tools registered: " (count all-tools) ":\n"
                     (with-out-str (pprint (mapv :name all-tools)))))
    (swap! components-atm #(assoc % :tools all-tools))
    all-tools))

;; Delegate to prompts namespace
(defn make-prompts! [config-map]
  (let [prompts (prompts/make-prompts config-map)]
    (log! :info (str "Total prompts registered: " (count prompts) ":\n"
                     (with-out-str (pprint (mapv :name prompts)))))
    (swap! components-atm #(assoc % :prompts prompts))
    prompts))

;; Delegate to resources namespace
(defn make-resources!
  "Create schedMCP-specific resources.
   Delegates to sched-mcp.resources namespace."
  [config-map]
  (let [resources (resources/make-resources! config-map)]
    (log! :info (str "Total resources registered: " (count resources) ":\n"
                     (with-out-str (pprint (mapv :name resources)))))
    (swap! components-atm #(assoc % :resources resources))
    resources))

;;; ------------------------------ Starting and stopping -------------------------

;;; I'm thinking something like this might work. It doesn't touch the configs though (good thing? bad thing?).
;;; Currently it generates errors: SLF4J/ERROR  : - Operator called default onErrorDropped
(defn ^:diag reload-components!
  "Reload configuration and re-register components without stopping the server.
   Probably for development use only."
  []
  (let [components (build-components {:make-tools-fn make-tools!
                                      :make-prompts-fn make-prompts!
                                      :make-resources-fn make-resources!})]
    (register-components! mcp-server components)))

(def server-promise
  "This is used in main to keep the server from exiting immediately."
  (p/deferred))

(defn start-mcp-server []
  (try
    (reset! mcp-server-atm
            (build-and-start-mcp-server
             {:make-tools-fn make-tools!
              :make-prompts-fn make-prompts!
              :make-resources-fn make-resources!}))
    (catch Exception e
      (log! :error (str "Server exception: " e))
      (p/resolve! server-promise :failed-to-start))))

(defn stop-mcp-server
  "Stop the MCP server"
  []
  (log! :info "Stopping schedMCP server...")
  (close-mcp-server!)
  (p/resolve! server-promise :exited))

;;; Starting and stopping
(defstate mcp-core-server
  :start (start-mcp-server)
  :stop (stop-mcp-server))
