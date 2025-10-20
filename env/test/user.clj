(ns user
  "Development namespace for schedMCP"
  (:require
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as tools-ns :refer [refresh set-refresh-dirs]]
   [expound.alpha :as expound]
   [mount.core :as mount]
   [sched-mcp.mcp-core]  ; for mount
   [sched-mcp.system-db] ; for mount
   [taoensso.telemere :as tel]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(set-refresh-dirs "src/schedMCP" #_"test/schedMCP") ; Put here as many as you need. test messes with ns-setup!
(s/check-asserts true) ; Error on s/assert, run s/valid? rather than just returning the argument.
(tel/call-on-shutdown! tel/stop-handlers!)

(defn ^:diag start
  "Start the schedMCP system for development"
  []
  (mount/start)
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

(start)
