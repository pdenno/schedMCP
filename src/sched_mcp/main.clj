(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]
   [promesa.core :as p]
   [sched-mcp.mcp-core :as mcore]
   [sched-mcp.util :refer [alog! log! now]]))

(defn -main
  "Start the schedMCP server. This will block on the MCP server listen call."
  [& _args]
  (binding [*out* *err*]
    (alog! (str "Starting schedMCP from -main " (now)))
    (try
      ;; Start all components, including the MCP server.
      (mount/start)

      ;; Wait for MCP server to p/resolve! this promise, which it does on exiting.
      (p/await mcore/server-promise)
      ;; This line will only be reached if the server stops
      (log! :info "MCP server terminating")

      (catch Exception e
        (log! :error (str "***Failed to start server: " (.getMessage e)))
        (.printStackTrace e)
        (mount/stop #'sched-mcp.mcp-core/mcp-core-server)
        (System/exit 1))
      (finally
        (log! :info "Executing finally on main.")
        (mount/stop #'sched-mcp.mcp-core/mcp-core-server)
        (System/exit 0)))))
