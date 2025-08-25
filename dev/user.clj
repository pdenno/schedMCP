(ns user
  "Development namespace for schedMCP"
  (:require
   [clojure.tools.namespace.repl :refer [refresh refresh-all]]
   [mount.core :as mount]
   [sched-mcp.core :as core]
   [sched-mcp.main :as main]
   [sched-mcp.sutil :refer [log!]]))

(defn start
  "Start the schedMCP system for development"
  []
  (mount/start)
  (log! :info "Mount components started. System ready for MCP connections.")
  (log! :info "To start MCP server, run: clojure -M -m sched-mcp.main")
  :started)

(defn stop
  "Stop the system"
  []
  (mount/stop)
  :stopped)

(defn restart
  "Stop, refresh code, and start again"
  []
  (stop)
  (refresh :after 'user/start))

(defn start-mcp-server
  "Start the MCP server in the REPL for testing"
  []
  (log! :info "Starting MCP server in REPL...")
  (future (main/-main)))

;; Helpful development functions
(defn test-interview-start
  "Test starting an interview directly"
  []
  (require '[sched-mcp.tools.interview :as tools])
  (let [tool-fn (:tool-fn tools/start-interview-tool-spec)]
    (tool-fn {:project_name "REPL Test Brewery"
              :domain "food-processing"})))

(defn test-get-context
  "Test getting interview context"
  [project-id]
  (require '[sched-mcp.tools.interview :as tools])
  (let [tool-fn (:tool-fn tools/get-interview-context-tool-spec)]
    (tool-fn {:project_id project-id})))

;; Print available commands
(println "\nschedMCP Development Environment")
(println "================================")
(println "Available commands:")
(println "  (start)           - Start mount components")
(println "  (stop)            - Stop mount components")
(println "  (restart)         - Refresh code and restart")
(println "  (start-mcp-server) - Start MCP server in REPL")
(println "\nTest functions:")
(println "  (test-interview-start) - Test interview creation")
(println "  (test-get-context \"project-id\") - Test context retrieval")
