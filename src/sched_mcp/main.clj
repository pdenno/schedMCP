(ns sched-mcp.main
  "Main entry point for schedMCP server"
  (:require
   [mount.core :as mount]
   [sched-mcp.tools.registry :as registry]
   [sched-mcp.mcp-core :as mcp-core]))

(def server-info
  {:name "schedMCP"
   :version "0.1.0"})

  ;; Start MCP server with our tools
(def server-config
  {:tool-specs registry/tool-specs
   :server-info server-info})

(defn -main
  "Start the schedMCP server"
  [& _args]
  ;; Initialize mount components (for database connections, etc.)
  (mount/start)
  ;; Run the server loop in the main thread to prevent exit
  (mcp-core/run-server server-config))
