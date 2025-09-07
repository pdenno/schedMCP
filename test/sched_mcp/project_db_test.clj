(ns sched-mcp.project-db-test
  "Test namespace for project database functions and migrations
   Migration functions are throw-away but useful to keep here for adaptation"
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.sutil :refer [connect-atm]]
   [datahike.api :as d]))

;;; -------------------- Migration Functions --------------------
;;; These are throw-away functions but useful to keep as templates

(defn check-project-migration-status
  "Check which projects need migration"
  []
  (println "\n=== Checking Project Migration Status ===")
  (doseq [pid (pdb/list-projects)]
    (println (str "\nProject: " pid))
    (try
      (let [conn @(connect-atm pid)
            ;; Check for old schema attributes
            has-old-eads? (d/q '[:find ?e .
                                 :where
                                 (or [?e :conversation/current-eads]
                                     [?e :message/eads-id]
                                     [?e :eads/id])]
                               conn)
            ;; Check for new schema attributes
            has-new-ds? (d/q '[:find ?e .
                               :where
                               (or [?e :conversation/active-ds-id]
                                   [?e :message/pursuing-ds]
                                   [?e :dstruct/id])]
                             conn)]
        (println (str "  Old EADS attributes: " (if has-old-eads? "FOUND" "none")))
        (println (str "  New DS attributes: " (if has-new-ds? "FOUND" "none")))
        (println (str "  Status: " (cond
                                     (and has-old-eads? (not has-new-ds?)) "NEEDS MIGRATION"
                                     (and has-old-eads? has-new-ds?) "PARTIALLY MIGRATED"
                                     has-new-ds? "MIGRATED"
                                     :else "EMPTY"))))
      (catch Exception e
        (println (str "  ERROR: " (.getMessage e)))))))

;;; -------------------- Tests --------------------
(deftest test-project-backup
  (testing "Project backup creates readable EDN files"
    (when-let [pid (first (pdb/list-projects))]
      (pdb/backup-project-db pid :target-dir "test/data/")
      (let [backup-file (str "test/data/" (name pid) ".edn")]
        (is (.exists (io/file backup-file)))

        ;; Verify we can read it back
        (let [content (slurp backup-file)
              data (read-string content)]
          (is (vector? data))
          (is (= 1 (count data)))
          (is (map? (first data)))
          (is (= pid (:project/id (first data)))))))))

(deftest test-schema-cleaning
  (testing "Schema cleaning preserves valid attributes and drops invalid ones"
    (let [test-data {:project/id :test
                     :project/name "Test"
                     :invalid/attr "should be removed"
                     :conversation/messages [{:message/id 1
                                              :message/content "test"
                                              :invalid/msg-attr "removed"}]}
          cleaned (pdb/clean-project-for-schema test-data)]

      ;; Valid attributes kept
      (is (= :test (:project/id cleaned)))
      (is (= "Test" (:project/name cleaned)))

      ;; Invalid attributes removed
      (is (nil? (:invalid/attr cleaned))))))

(comment
  ;; Manual migration workflow:

  ;; 1. Check what needs migration
  (check-project-migration-status)

  ;; 2. Run the migration
  ;(migrate-projects-to-new-schema)

  ;; 3. Verify migration
  (check-project-migration-status)

  ;; For a single project:
  (let [pid :craft-beer]
    (pdb/backup-project-db pid)
    (pdb/update-project-for-schema! pid)))
