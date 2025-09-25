(ns sched-mcp.mcp-core
  "Core MCP server implementation for schedMCP using Java MCP SDK.
   Based on clojure-mcp approach for compatibility."
  (:require
   [clojure.data.json :as json]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [sched-mcp.project-db]                    ; For mount
   [sched-mcp.system-db]                     ; For mount
   [sched-mcp.tools.iviewr.discovery-schema] ; For mount
   [sched-mcp.tools.iviewr-tools]            ; For mount
   [sched-mcp.llm]                           ; For mount
   [sched-mcp.tools.registry :as registry]   ; For mount
   [sched-mcp.util :as util :refer [log!]])
  (:import [io.modelcontextprotocol.server.transport
            StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(def ^:private object-mapper (ObjectMapper.))

;;; Helper functions from clojure-mcp

(defn create-mono-from-callback
  "Create a Mono that completes when callback is invoked"
  [callback-fn]
  (Mono/create
   (reify java.util.function.Consumer
     (accept [_ sink]
       (try
         (let [result (callback-fn)]
           (.success sink result))
         (catch Exception e
           (.error sink e)))))))

(defn- adapt-result
  "Adapt a tool result to MCP format"
  [result]
  (McpSchema$CallToolResult.
   (cond
     (string? result)
     [(McpSchema$TextContent. result)]

     (map? result)
     (if (:error result)
       [(McpSchema$TextContent. (str "Error: " (:error result)))]
       [(McpSchema$TextContent. (json/write-str result :pretty true))])

     :else
     [(McpSchema$TextContent. (str result))])
   nil))

(defn adapt-results
  "Convert tool results to MCP format, handling errors"
  [result error?]
  (if error?
    (McpSchema$CallToolResult.
     [(McpSchema$TextContent. (str "Tool error: " result))]
     nil)
    (adapt-result result)))

(defn create-async-tool
  "Create an async tool specification from our tool map"
  [{:keys [name description schema tool-fn]}]
  (let [schema-json (json/write-str schema)
        tool (McpSchema$Tool. name description schema-json)]
    (McpServerFeatures$AsyncToolSpecification.
     tool
     (reify java.util.function.BiFunction
       (apply [_ _exchange arguments]
         (create-mono-from-callback
          (fn []
            (try
              (let [args (when arguments
                           (json/read-str (.toString arguments) :key-fn keyword))
                    _ (log! :info (str "Executing tool: " name " with args: " (pr-str args)))
                    result (tool-fn args)]
                (log! :info (str "Tool " name " completed successfully"))
                (adapt-results result false))
              (catch Exception e
                (log! :error (str "Tool " name " failed: " (.getMessage e)))
                (adapt-results (.getMessage e) true))))))))))

(defn add-tool
  "Add a tool to the MCP server"
  [mcp-server tool-map]
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe)))

(defn create-server-info
  "Create server information"
  []
  {:name "schedMCP"
   :version "0.1.0"})

(defn create-server-capabilities
  "Create server capabilities"
  []
  (-> (McpSchema$ServerCapabilities/builder)
      (.tools true)
      (.build))) ; _schemas

(defn mcp-server
  "Create and configure the MCP server"
  []
  (let [transport-provider (StdioServerTransportProvider. object-mapper)
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "schedMCP" "0.1.0")
                   (.capabilities (create-server-capabilities))
                   (.build))]
    ;; Add all tools from registry
    (doseq [tool-spec registry/tool-specs]
      (add-tool server tool-spec))
    server))

;;; Server lifecycle management

(def server-instance (atom nil))
(def server-transport (atom nil))
(def server-promise (p/deferred))

(defn start-server
  "Start the MCP server"
  []
  (try
    (log! :info "Starting schedMCP server...")

    ;; Create MCP server
    (let [server (mcp-server)]

      ;; Store reference
      (reset! server-instance server)

      ;; The server is already listening via StdioServerTransportProvider
      ;; No need to call .listen separately

      (log! :info "MCP server started successfully")
      server)
    (catch Exception e
      (log! :error (str "Failed to start server: " (.getMessage e)))
      (p/resolve! server-promise :failed-to-start)
      (throw e))))

(defn stop-server
  "Stop the MCP server"
  []
  (log! :info "Stopping schedMCP server...")
  (when-let [server @server-instance]
    (.closeGracefully server))
  (p/resolve! server-promise :exiting)
  (reset! server-instance nil)
  (reset! server-transport nil)
  (log! :info "MCP server stopped"))

;;; Starting and stopping
(defstate mcp-core-server
  :start (start-server)
  :stop (stop-server))
