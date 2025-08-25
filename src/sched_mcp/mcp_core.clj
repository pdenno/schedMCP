(ns sched-mcp.mcp-core
  "Core MCP server implementation for schedMCP"
  (:require
   [clojure.data.json :as json]
   [mount.core :as mount :refer [defstate]]
   [sched-mcp.util :as util :refer [log!]]) ; for mount
  (:import [java.io BufferedReader InputStreamReader PrintWriter]))

(def ^:diag diag (atom nil))

;; Create readers/writers once to maintain connection
(def stdin-reader (BufferedReader. (InputStreamReader. System/in)))
(def stdout-writer (PrintWriter. System/out true))

;;; JSON-RPC message handling

(defn read-json-rpc
  "Read a JSON-RPC message from stdin"
  []
  (when-let [line (.readLine stdin-reader)]
    (try
      (json/read-str line :key-fn keyword)
      (catch Exception e
        (log! :error (str "Failed to parse JSON-RPC: " (.getMessage e)))
        nil))))

(defn write-json-rpc
  "Write a JSON-RPC message to stdout"
  [message]
  (.println stdout-writer (json/write-str message))
  (.flush stdout-writer))

(defn error-response [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code :message message}})

(defn success-response [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

;;; Tool handling

(defn tool->mcp-tool
  "Convert internal tool spec to MCP tool format"
  [{:keys [name description schema]}]
  {:name name
   :description description
   :inputSchema schema})

(defn call-tool
  "Call a tool function with arguments"
  [tool-spec arguments]
  (try
    (let [result ((:tool-fn tool-spec) arguments)]
      (if (:error result)
        {:error true :content [{:type "text" :text (:error result)}]}
        {:content [{:type "text" :text (json/write-str result :pretty true)}]}))
    (catch Exception e
      {:error true :content [{:type "text" :text (str "Tool error: " (.getMessage e))}]})))

;;; MCP protocol handlers

(defn handle-initialize
  "Handle the initialize request"
  [id params server-info]
  (log! :info "MCP server initializing")
  (success-response id
                    {:protocolVersion "2025-06-18" ; Match client's version
                     :capabilities {:tools {}}
                     :serverInfo server-info}))

(defn handle-tools-list
  "Handle tools/list request"
  [id tool-specs]
  (success-response id
                    {:tools (mapv tool->mcp-tool tool-specs)}))

(defn handle-tool-call
  "Handle tools/call request"
  [id tool-name arguments tool-specs]
  (log! :info (str "Tool call: " tool-name))
  (if-let [tool-spec (first (filter #(= (:name %) tool-name) tool-specs))]
    (let [result (call-tool tool-spec arguments)]
      (success-response id result))
    (error-response id -32601 (str "Unknown tool: " tool-name))))

;;; Main server loop

(defn handle-request
  "Route requests to appropriate handlers"
  [request {:keys [tool-specs server-info]}]
  (let [{:keys [id method params]} request]
    ;; Handle notifications (no id field)
    (if (nil? id)
      ;; Notifications don't get responses
      (case method
        "notifications/initialized" nil ; Acknowledge but don't respond
        (log! :warn (str "Unknown notification: " method)))
      ;; Handle requests (with id field)
      (case method
        "initialize" (handle-initialize id params server-info)
        "tools/list" (handle-tools-list id tool-specs)
        "tools/call" (handle-tool-call id (:name params) (:arguments params) tool-specs)
        ;; Unknown method
        (error-response id -32601 (str "Method not found: " method))))))

(defn run-server
  "Main server loop. Don't do any output to console here except JSON-RPC!"
  [{:keys [_tool-specs _server-info] :as config}]
  (try
    (loop []
      (if-let [request (read-json-rpc)]
        (do
          (try
            (let [response (handle-request request config)]
              ;; Only write response if it's not nil (notifications return nil)
              (when response
                (write-json-rpc response)))
            (catch Exception e
              (log! :error (str "Error handling request: " (.getMessage e) "\n" (pr-str request)))
              (when-let [id (:id request)]
                (write-json-rpc (error-response id -32603 "Internal error")))))
          (recur))
        ;; If read-json-rpc returns nil, the connection is closed
        (log! :info "Connection closed by client")))
    (catch Exception e
      (log! :error (str "Server error: " (.getMessage e))))
    (finally
      (log! :info "Server shutting down"))))

(defn start-server
  "A no-op"
  []
  :started)

(defn stop-server
  []
  (log! :info "Stopping MCP server.")
  (shutdown-agents))

(defstate mcp-core-server
  :start (start-server)
  :stop  (stop-server))
