(ns develop.nrepl
  "Start an nrepl server. Might be useful when used with clojure-mcp but not starting with -M:nrepl."
  (:require
   [cider.nrepl :refer [cider-middleware]]
   [nrepl.server :as server]
   [sched-mcp.util :refer [log!]]))

;;;----------------------------------- nrepl server (for debugging) ----------------------
(def cider-handler (apply server/default-handler cider-middleware))

(defonce nrepl-server (atom nil))

(defn ^:admin start-nrepl-server []
  (log! :info "About to start nREPL server...")
  (log! :debug "About to start nREPL server...")
  (try
    (if @nrepl-server
      (log! :warn "nREPL server has already been started.")
      (do
        (reset! nrepl-server (server/start-server :port 7888
                                                  :bind "127.0.0.1"
                                                  :handler cider-handler))
        (log! :info (str "Started nREPL server on port 7888: " @nrepl-server))))
    (catch Exception e
      (log! :info (str "Failed to start nREPL: " (.getMessage e))))))

(defn ^:admin stop-nrepl-server []
  (server/stop-server @nrepl-server)
  (reset! nrepl-server nil))

(defn ^:admin start-nrepl-server []
  (when-not @nrepl-server (start-nrepl-server)))




  
