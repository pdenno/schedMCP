(ns sched-mcp.util
  "Do lowest level configuration (logging, etc.) used by both the server and app."
  (:require
   [clojure.string :as str]
   #?@(:clj [[taoensso.telemere.tools-logging :as tel-log]])
   [mount.core :as mount :refer [defstate]]
   [taoensso.telemere :as tel]
   [taoensso.timbre :as timbre])) ; To stop pesky datahike :debug messages.

(def ^:diag diag (atom nil))

(defn agents-log-output-fn
  "Output verbatim agent interactions to a log file."
  ([] :can-be-a-no-op)
  ([signal]
   (when (= (:kind signal) :agents)
     (let [{:keys [msg_]} signal
           msg (if-let [s (not-empty (force msg_))] s "\"\"")]
       (str msg "\n")))))

(defn alog!
  "Log info-string to agent log. Using this, and not console, is essential to MCP server operation."
  ([msg-text] (alog! msg-text {}))
  ([msg-text {:keys [level] :or {level :info}}]
   (tel/with-kind-filter {:allow :agents}
     (tel/signal!
      {:kind :agents, :level (or level :info), :msg msg-text}))
   nil))

#?(:clj
   (defn now [] (new java.util.Date))
   :cljs
   (defn now [] (.now js/Date) #_(.getTime (js/Date.))))

(defn config-log!
  "Configure Telemere: set reporting levels and specify a custom :output-fn.
   Only MCP can use console out."
  []
  ;; Remove any default console handler that might exist
  (tel/remove-handler! :default/console)
  ;; Only MCP messages should go to stdout.
  #?(:clj (tel/add-handler! :agent/log (tel/handler:file {:output-fn agents-log-output-fn
                                                          :path "./logs/agents-log.edn"
                                                          :interval :daily}))
     (tel-log/tools-logging->telemere!) ;; Send tools.logging through telemere. Check this with (tel/check-interop)
     (tel/streams->telemere!)) ;; likewise for *out* and *err* but "Note that Clojure's *out*, *err* are not necessarily automatically affected."
  ;; The following is needed because of datahike; no timbre-logging->telemere!
  (timbre/set-config! (assoc timbre/*config* :min-level [[#{"datahike.*"} :error]
                                                         [#{"konserve.*"} :error]]))
  (alog! (str "======= Starting. config-log! executed " (now) " ==========")))

(defn ^:diag unconfig-log!
  "Set :default/console back to its default handler. Typically done at REPL."
  []
  (tel/remove-handler! :default/console)
  #_(tel/add-handler! :default/console (tel/handler:console)))


;;; This seems to cause problems in recursive resolution. (See resolve-db-id)"
(defn db-ref?
  "It looks to me that a datahike ref is a map with exactly one key: :db/id."
  [obj]
  (and (map? obj) (= [:db/id] (keys obj))))

(defn remove-src-markers
  "Remove the #+begin_src and #+end_src markers from the string."
  [s]
  (->> s
       str/split-lines
       (remove (fn [line]
                 (or (re-matches #"^\s*#\+begin_src.*" line)
                     (re-matches #"^\s*#\+end_src.*" line))))
       (str/join "\n")))

;;; -------------- Starting and stopping ----------------------
(defn init-util []
  (config-log!))

(defstate util-state
  :start (init-util))
