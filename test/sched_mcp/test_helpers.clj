(ns sched-mcp.test-helpers
  "Helper functions for testing the schedMCP system"
  (:require
   [clojure.pprint :refer [pprint]]
   [mount.core :as mount]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :as util :refer [log!]]))

(defn start-test-system!
  "Start the system for testing"
  []
  (mount/start)
  ;; The db defstate will automatically initialize system DB and register all project DBs
  (log! :info "Test system started")
  (log! :info (str "Loaded " (count (pdb/list-projects)) " projects")))

(defn stop-test-system!
  "Stop the test system"
  []
  (mount/stop)
  (log! :info "Test system stopped"))

(defn create-sample-projects!
  "Create several sample projects for testing"
  []
  (let [projects [{:pid "brewery-1"
                   :project-name "Craft Beer Brewery"
                   :domain "food-processing"}
                  {:pid "glass-factory"
                   :project-name "Specialty Glass Manufacturing"
                   :domain "glass-manufacturing"}
                  {:pid "pencil-factory"
                   :project-name "Premium Pencil Production"
                   :domain "wood-products"}]]
    (doseq [proj projects]
      (pdb/create-projectdb! proj)
      (log! :info (str "Created project: " (:project-id proj))))
    projects))
