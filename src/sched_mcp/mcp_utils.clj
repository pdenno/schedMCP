(ns sched-mcp.mcp-utils
  "MCP-specific utility functions for safe logging and output"
  (:require
   [sched-mcp.util :as util :refer [log!]]))

(defn mcp-log!
  "Log to stderr for MCP debugging - appears in MCP server logs without corrupting protocol"
  [msg]
  (binding [*out* *err*]
    (println msg)))

(defn safe-println
  "Print safely based on context - to stderr in MCP, stdout otherwise"
  [& args]
  (if (System/getProperty "mcp.server")
    (apply mcp-log! args)
    (apply println args)))

(defn safe-pprint
  "Pretty print safely based on context"
  [obj]
  (require '[clojure.pprint :refer [pprint]])
  (if (System/getProperty "mcp.server")
    (mcp-log! (with-out-str (pprint obj)))
    (pprint obj)))
