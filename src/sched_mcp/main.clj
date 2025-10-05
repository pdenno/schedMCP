(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core         :as mount]
   [promesa.core       :as p]
   [sched-mcp.mcp-core :as mcore]
   [sched-mcp.util     :refer [alog! log! now]]))

(defonce server-port
  ;; Used to ensure Clojure Desktop only starts one instance.
  (atom nil))

(defn claim-port
  "This is used to prohibit multiple execution starts, which has been a problem when started by Claude Desktop."
  []
  (try
    (let [server (java.net.ServerSocket. 39847)]
      (reset! server-port server)
      true)
    (catch Exception _e
      false)))

(defn -main
  "Start the schedMCP server. This will block on the MCP server listen call."
  [& _args]
  (alog! (str "Starting schedMCP from -main " (now)))
  (if (claim-port)
    (try
      (mount/start) ;; Start all components, including the MCP server.
      (p/await mcore/server-promise)

      (catch Exception e
        (log! :error (str "***Failed to start server: " (.getMessage e)))
        (.printStackTrace e)
        (mount/stop #'sched-mcp.mcp-core/mcp-core-server)
        (System/exit 1))
      (finally
        (log! :info "Exiting: Executing finally on main.")
        (mount/stop #'sched-mcp.mcp-core/mcp-core-server)
        (System/exit 0)))
    (do
      (alog! "Another instance is already running. Exiting.")
      (System/exit 0))))
