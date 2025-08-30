(ns sched-mcp.test-helpers
  "Helper functions for testing the schedMCP system"
  (:require
   [clojure.pprint :refer [pprint]]
   [mount.core :as mount]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.tools.interviewer.core :as itools]
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
  (let [projects [{:project-id "brewery-1"
                   :project-name "Craft Beer Brewery"
                   :domain "food-processing"}
                  {:project-id "glass-factory"
                   :project-name "Specialty Glass Manufacturing"
                   :domain "glass-manufacturing"}
                  {:project-id "pencil-factory"
                   :project-name "Premium Pencil Production"
                   :domain "wood-products"}]]
    (doseq [proj projects]
      (pdb/create-project! proj)
      (log! :info (str "Created project: " (:project-id proj))))
    projects))

(defn test-discovery-schema-flow
  "Test the Discovery Schema flow for a project"
  [project-id conversation-id]
  (log! :info "\n=== Testing Discovery Schema Flow ===")

  ;; Start with warm-up DS
  (let [ds-id "process/warm-up-with-challenges"
        _ (log! :info (str "\nStarting DS: " ds-id))

        ;; Formulate first question
        question nil ; (itools/formulate-question project-id conversation-id ds-id) <==================== No such function
        _ (do (log! :info "\nFormulated question:")
              (pprint question))

        ;; Simulate user answer
        answer "We make craft beer and our main challenge is coordinating multiple brewing tanks with different batch sizes and fermentation times."

        ;; Interpret the response
        interpretation nil ; (itools/interpret-response project-id conversation-id ds-id answer (:question question)) <==================== No such function
        _ (do (log! :info "\nInterpreted response:")
              (pprint interpretation))]

    {:project-id project-id
     :conversation-id conversation-id
     :ds-id ds-id
     :question question
     :answer answer
     :interpretation interpretation}))
