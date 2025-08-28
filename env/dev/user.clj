(ns user
  "Development namespace for schedMCP"
  (:require
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as tools-ns :refer [refresh set-refresh-dirs]]
   [develop.repl :refer [ns-setup! undo-ns-setup!]] ; for use at REPL.
   [expound.alpha :as expound]
   [lambdaisland.classpath.watch-deps :as watch-deps] ; hot loading for deps.
   [mount.core :as mount]
   [sched-mcp.tools.iviewr-tools :as itools]
   [sched-mcp.mcp-core] ; for mount
   [sched-mcp.util :refer [log!]]
   [taoensso.telemere :as tel]))

[ns-setup! undo-ns-setup!] ; for mount

;;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(add-tap (bound-fn* clojure.pprint/pprint))
(set-refresh-dirs "src/schedMCP" #_"test/schedMCP") ; Put here as many as you need. test messes with ns-setup!
(s/check-asserts true) ; Error on s/assert, run s/valid? rather than just returning the argument.
(tel/call-on-shutdown! tel/stop-handlers!)

(defn ^:diag start
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

(defn ^:diag restart
  "Stop, refresh code, and start again"
  []
  (stop)
  (refresh :after 'user/start))

;; Helpful development functions
(defn ^:diag test-interview-start
  "Test starting an interview directly"
  []
  (require '[sched-mcp.tools.interview :as tools])
  (let [tool-fn (:tool-fn itools/start-interview-tool-spec)]
    (tool-fn {:project_name "REPL Test Brewery"
              :domain "food-processing"})))

(defn ^:diag test-get-context
  "Test getting interview context"
  [project-id]
  (require '[sched-mcp.tools.interview :as tools])
  (let [tool-fn (:tool-fn itools/get-interview-context-tool-spec)]
    (tool-fn {:project_id project-id})))
