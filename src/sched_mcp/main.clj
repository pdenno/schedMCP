(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]))

(defn -main
  "Start everything including the schedMCP server.
   If instead, you just want to start or stop the mcp-server use
    (mount/start #'sched-mcp.mcp-core.mcp-core-server)
    (mount/stop #'sched-mcp.mcp-core.mcp-core-server)."
  [& _args]
  ;; Run the server loop in a future to prevent exit and provide stdout.
  (mount/start))
