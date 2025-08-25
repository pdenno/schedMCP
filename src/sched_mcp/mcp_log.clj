(ns sched-mcp.mcp-log
  "Logging configuration for MCP server - ensures all output goes to stderr"
  (:require
   [taoensso.telemere :as tel]))

(defn configure-mcp-logging!
  "Configure logging for MCP server - all logs must go to stderr"
  []
  ;; Configure Telemere to output to stderr only
  (tel/set-min-level! :info)
  (tel/add-handler! :stderr
                    {:output-fn (tel/pr-signal-fn {:pr-fn :edn})
                     :handler-fn (fn [signal]
                                   (binding [*out* *err*] ; Force output to stderr
                                     (println (tel/format-signal signal))))}))

(defn log-stderr!
  "Log directly to stderr for MCP compatibility"
  [level msg]
  (binding [*out* *err*]
    (println (str (java.time.Instant/now) " [" (name level) "] " msg))))
