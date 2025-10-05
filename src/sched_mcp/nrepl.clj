(ns sched-mcp.nrepl
  "Start an nrepl server."
  (:require
   [cider.nrepl :refer [cider-middleware]]
   [mount.core :as mount :refer [defstate]]
   [nrepl.server :as server]
   [sched-mcp.util :refer [log!]]))

;;;----------------------------------- nrepl server (for debugging) ----------------------
(def cider-handler (apply server/default-handler cider-middleware))

(defonce nrepl-server (atom nil))

(defn start-nrepl-server []
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

(defn stop-nrepl-server []
  (server/stop-server @nrepl-server)
  (reset! nrepl-server nil))

(defstate nrepl-server
  :start (start-nrepl-server)
  :stop (stop-nrepl-server))
