(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]
   [nrepl.server :as nrepl]
   [promesa.core :as p]
   [sched-mcp.project-db]                    ; For mount
   [sched-mcp.system-db]                     ; For mount
   [sched-mcp.tools.iviewr.discovery-schema] ; For mount
   [sched-mcp.tools.iviewr-tools]            ; For mount
   [sched-mcp.llm]                           ; For mount
   [sched-mcp.tools.registry :as registry]   ; For mount
   [sched-mcp.mcp-core :as mcore]
   [sched-mcp.util :refer [alog! log! now]]))

(defonce server-port (atom nil))

(defn claim-port []
  (try
    (let [server (java.net.ServerSocket. 39847)]
      (reset! server-port server)
      true)
    (catch Exception _e
      false)))

(defonce nrepl-server (atom nil))

(defn -main
  "Start the schedMCP server. This will block on the MCP server listen call."
  [& _args]
  (alog! (str "Starting schedMCP from -main " (now)))
  (if (claim-port)
    (try
      (when-not @nrepl-server
        (reset! nrepl-server (nrepl/start-server :port 7888))
        (alog! "Started nREPL server on port 7888"))
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
        (System/exit 0)))
    (do
      (alog! "Another instance is already running, exiting")
      (System/exit 0))))
