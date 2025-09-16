(ns sched-mcp.util
  "Do lowest level configuration (logging, etc.) used by both the server and app."
  (:require
   [bling.core :as bling :refer [bling print-bling]] ; print-pling is used (clj)!
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [taoensso.telemere.tools-logging :as tel-log]
   [mount.core :as mount :refer [defstate]]
   [taoensso.telemere :as tel]
   [taoensso.timbre :as timbre])) ; To stop pesky datahike :debug messages.

(def ^:diag diag (atom nil))

(defn now [] (new java.util.Date))

(defn agents-log-output-fn
  "Output verbatim agent interactions to a log file."
  ([] :can-be-a-no-op)
  ([signal]
   (when (= (:kind signal) :agents)
     (let [{:keys [msg_]} signal
           msg (if-let [s (not-empty (force msg_))] s "\"\"")]
       (str (now) " " msg "\n")))))

(defn alog!
  "Log info-string to agent log. Using this, and not console, is essential to MCP server operation."
  ([msg-text] (alog! msg-text {}))
  ([msg-text {:keys [level] :or {level :info}}]
   (binding [*out* *err*] ; Perhaps not necessary; there was a call before (mount/start).
     (tel/with-kind-filter {:allow :agents}
       (tel/signal!
        {:kind :agents, :level (or level :info), :msg msg-text}))
     nil)))

(defn log!
  "This is to keep cider stepping from stumbling over the telemere log! macro."
  [log-key s]
  ;;(tel/log! log-key s)
  (alog! s {:level log-key}))

(defn print-bling*
  "Like print-bling but outputs to *err* instead of *out*"
  [bling-form]
  (binding [*out* *err*]
    (print-bling bling-form)))

(defn custom-console-output-fn
  "I don't want to see hostname and time, etc. in console logging."
  ([] :can-be-a-no-op) ; for shutdown, at least.
  ([signal]
   (when-not (= (:kind signal) :agents)
     (let [{:keys [kind level location msg_]} signal
           file (:file location)
           file (when (string? file)
                  (let [[_ stbd-file] (re-matches #"^.*(sched-mcp.*)$" file)]
                    (or stbd-file file)))
           line (:line location)
           msg (if-let [s (not-empty (force msg_))] s "\"\"")
           heading (-> (str (name kind) "/" (name level) " ") str/upper-case)]
       (cond (= :error level) (print-bling* (bling [:bold.red.white-bg heading] " " [:red (str file ":" line " - " msg)]))
             (= :warn level) (print-bling* (bling [:bold.blue heading] " " [:yellow (str file ":" line " - " msg)]))
             :else (print-bling* (bling [:bold.blue heading] " " [:olive (str file ":" line " - " msg)])))))))

(defn config-log!
  "Configure Telemere: set reporting levels and specify a custom :output-fn.
   Only MCP can use console out."
  []
  (binding [*out* *err*]
    ;; Remove any default console handler that might exist
    (tel/remove-handler! :default/console)
    (tel/add-handler! :default/console (tel/handler:console {:stream *err* :output-fn custom-console-output-fn}))
    (tel/add-handler! :agent/log (tel/handler:file {:output-fn agents-log-output-fn
                                                    :path "./logs/agents-log.edn"
                                                    :interval :daily}))
    (tel-log/tools-logging->telemere!) ;; Send tools.logging through telemere. Check this with (tel/check-interop)
    (tel/streams->telemere!)
    (tel/event! ::config-log {:level :info :msg (str "Logging configured:\n" (with-out-str (pprint (tel/check-interop))))})
    ;; The following is needed because of datahike; no timbre-logging->telemere!
    (timbre/set-config! (assoc timbre/*config* :min-level [[#{"datahike.*"} :error]
                                                           [#{"konserve.*"} :error]]))
    (log! :info (str "======= Starting. config-log! executed " (now) " =========="))))

(defn ^:diag unconfig-log!
  "Set :default/console back to its default handler. Typically done at REPL."
  []
  (tel/remove-handler! :default/console))

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
