(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]
   [sched-mcp.mcp-core :as mcp-core]))

(defn -main
  "Start the schedMCP server"
  [& _args]
  ;; Initialize mount components (for database connections, etc.)
  (mount/start)
  ;; Run the server loop in the main thread to prevent exit
  (mcp-core/run-server mcp-core/server-config))
