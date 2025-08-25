(ns user
  "For REPL-based start/stop of the server.
   This file isn't used in cljs and is a problem for shadow-cljs without the
   :clj compiler directives."
  (:require
   [clojure.pprint]
   [clojure.string]
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as tools-ns :refer [set-refresh-dirs]]
   [develop.repl :refer [ns-setup! undo-ns-setup!]] ; for use at REPL.
   [develop.dutil]
   [expound.alpha :as expound]
   [mount.core :as mount]
   [lambdaisland.classpath.watch-deps :as watch-deps] ; hot loading for deps.
   ;; ToDo: You need this to work
   [taoensso.telemere :as tel :refer [log!]]))

[ns-setup! undo-ns-setup!] ; for mount

;;; If you get stuck do: (clojure.tools.namespace.repl/refresh)

;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))
(add-tap (bound-fn* clojure.pprint/pprint))
(set-refresh-dirs "src/schedMCP" #_"test/schedMCP") ; Put here as many as you need. test messes with ns-setup!
(s/check-asserts true) ; Error on s/assert, run s/valid? rather than just returning the argument.
(tel/call-on-shutdown! tel/stop-handlers!)

;;; I don't use this. I start with
#_(defn ^:admin start
  "Start the MCP main loop and mount-based services."
  []
  (try
    (let [res (main/-main)
          info (str "   " (clojure.string/join ",\n    " (:started res)))]
      (log! :info (str "started:\n" info)))
    (catch Exception e
      (log! :error (str "start might fail with a UnresolvedAddressException if the internet connection is bad.\n" e)))))

(defn stop
  "Stop the web server"
  []
  (mount/stop))

(defn ^:admin restart
  "Stop, reload code, and restart the server. If there is a compile error, use:

  ```
  (tools-ns/refresh)
  ```

  to recompile, and then use `start` once things are good."
  []
  (stop)
  (tools-ns/refresh :after 'user/start))
