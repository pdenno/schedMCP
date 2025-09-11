(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]
   [sched-mcp.mcp-core :as mcp-core]
   [sched-mcp.util :refer [alog! log! now]]))

(defn -main
  "Start the schedMCP server. This will block on the MCP server listen call."
  [& _args]
  (alog! (str "Starting schedMCP " (now)))
  (try
    ;; Start mount components (but not the MCP server itself yet)
    (mount/start)

    ;; Now start the actual MCP server - this will block
    (log! :info "Starting MCP server from main...")
    (mcp-core/start-server)

    ;; This line will only be reached if the server stops
    (log! :info "MCP server terminated")
    (System/exit 0)

    (catch Exception e
      (log! :error (str "Failed to start server: " (.getMessage e)))
      (.printStackTrace e)
      (System/exit 1))))