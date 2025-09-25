(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [cider.nrepl :refer [cider-middleware]]
   [mount.core :as mount]
   [nrepl.server :as server]
   [promesa.core :as p]
   [sched-mcp.mcp-core :as mcore]
   [sched-mcp.util :refer [alog! log! now]]))

(def cider-handler
  (apply server/default-handler cider-middleware))

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
        (alog! "About to start nREPL server...")
        (try
          ;(binding [*out* *err*]
            (reset! nrepl-server (server/start-server :port 7888
                                                      :handler cider-handler
                                                      :bind "127.0.0.1")) ;)
          (alog! (str "Started nREPL server on port 7888: " @nrepl-server))
          (catch Exception e
          (alog! (str "Failed to start nREPL: " (.getMessage e))))))

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
      (alog! "Another instance is already running. Exiting.")
      (System/exit 0))))
