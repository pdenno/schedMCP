(ns sched-mcp.project-db-test
  "Test namespace for project database functions and migrations
   Migration functions are throw-away but useful to keep here for adaptation"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.db :as db]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.sutil :refer [connect-atm]]
   [datahike.api :as d]))

;;; -------------------- Migration Functions --------------------
;;; These are throw-away functions but useful to keep as templates

(defn migrate-projects-to-new-schema
  "Migrate all projects from old schema to new schema
   Adapts old attributes to new ones, particularly EADS -> DS renaming"
  []
  (println "\n=== Starting Project Migration ===")

  ;; First backup everything
  (println "\n1. Backing up all projects...")
  (pdb/backup-all-projects)
  (println "   Backups saved to data/projects/")

  ;; Get all projects
  (let [project-ids (db/list-projects)]
    (println (str "\n2. Found " (count project-ids) " projects to migrate: " project-ids))

    ;; Migrate each project
    (doseq [pid project-ids]
      (println (str "\n3. Migrating project: " pid))
      (try
        (let [;; Get the old project data
              old-proj (db/get-project pid)

              ;; Transform the data for new schema
              ;; Main transformations: EADS -> DS renaming
              new-proj (-> old-proj
                          ;; Rename conversation attributes
                           (update-in [:project/conversations]
                                      (fn [convs]
                                        (mapv (fn [conv]
                                                (-> conv
                                                   ;; :conversation/current-eads -> :conversation/active-ds-id
                                                    (clojure.set/rename-keys
                                                     {:conversation/current-eads :conversation/active-ds-id
                                                      :conversation/completed-eads :conversation/completed-ds})
                                                   ;; Update messages
                                                    (update :conversation/messages
                                                            (fn [msgs]
                                                              (mapv (fn [msg]
                                                                      (-> msg
                                                                         ;; Rename message attributes
                                                                          (clojure.set/rename-keys
                                                                           {:message/ds-id :message/pursuing-ds
                                                                            :message/eads-id :message/pursuing-ds})
                                                                         ;; Ensure :message/from uses new values
                                                                          (update :message/from
                                                                                  #(case %
                                                                                     :human :user
                                                                                     %))))
                                                                    (or msgs []))))
                                                   ;; Add missing attributes
                                                    (assoc :conversation/active-pursuit nil)))
                                              (or convs []))))

                          ;; Rename EADS references to DS
                           (clojure.set/rename-keys
                            {:project/active-eads-id :project/active-ds-id})

                          ;; Update any summary data structures
                           (update :project/summary-dstructs
                                   (fn [dstructs]
                                     (mapv (fn [ds]
                                             (-> ds
                                                 (clojure.set/rename-keys
                                                  {:eads/id :dstruct/id
                                                   :eads/data :dstruct/str
                                                   :eads/budget-left :dstruct/budget-left})))
                                           (or dstructs [])))))]

          ;; Recreate the project with new schema
          (println "   - Backing up original...")
          (pdb/backup-project-db pid :target-dir "data/projects/pre-migration/")

          (println "   - Recreating with new schema...")
          (pdb/recreate-project-db! pid new-proj)

          (println (str "   ✓ Successfully migrated " pid)))

        (catch Exception e
          (println (str "   ✗ ERROR migrating " pid ": " (.getMessage e)))
          (.printStackTrace e)))))

  (println "\n=== Migration Complete ==="))

(defn check-project-migration-status
  "Check which projects need migration"
  []
  (println "\n=== Checking Project Migration Status ===")
  (doseq [pid (db/list-projects)]
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
    (when-let [pid (first (db/list-projects))]
      (pdb/backup-project-db pid :target-dir "test/data/")
      (let [backup-file (str "test/data/" (name pid) ".edn")]
        (is (.exists (clojure.java.io/file backup-file)))

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
  (migrate-projects-to-new-schema)

  ;; 3. Verify migration
  (check-project-migration-status)

  ;; For a single project:
  (let [pid :craft-beer]
    (pdb/backup-project-db pid)
    (pdb/update-project-for-schema! pid)))
