(ns sched-mcp.backup-restore-test
  "Test backup and restore functionality for schema migrations"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.sutil :refer [connect-atm]]
   [datahike.api :as d]))

(deftest test-backup-restore
  (testing "Backup and restore preserves project data"
    ;; Assume we have some existing projects
    (let [projects (pdb/list-projects)]
      (when (seq projects)
        (let [test-pid (first projects)]
          ;; 1. Get original project data
          (let [original (pdb/get-project test-pid)]

            ;; 2. Backup the project
            (pdb/backup-project-db test-pid)

            ;; 3. Recreate from backup
            (pdb/recreate-project-db! test-pid)

            ;; 4. Get restored project data
            (let [restored (pdb/get-project test-pid)]

              ;; 5. Verify key data is preserved
              (is (= (:project/id original) (:project/id restored)))
              (is (= (:project/name original) (:project/name restored)))

              ;; 6. Check conversations are preserved
              (is (= (count (:project/conversations original))
                     (count (:project/conversations restored)))))))))))

(deftest test-schema-cleaning
  (testing "Schema cleaning removes obsolete attributes"
    (let [test-data {:project/id :test-proj
                     :project/name "Test Project"
                     :obsolete/attribute "should be removed"
                     :another/obsolete "also removed"
                     :project/conversations [{:conversation/id :test-conv
                                              :conversation/status :active
                                              :obsolete/conv-attr "removed"}]}
          cleaned (pdb/clean-project-for-schema test-data)]

      ;; Valid attributes preserved
      (is (= :test-proj (:project/id cleaned)))
      (is (= "Test Project" (:project/name cleaned)))

      ;; Obsolete attributes removed
      (is (nil? (:obsolete/attribute cleaned)))
      (is (nil? (:another/obsolete cleaned)))

      ;; Nested obsolete attributes also removed
      (let [conv (first (:project/conversations cleaned))]
        (is (= :test-conv (:conversation/id conv)))
        (is (nil? (:obsolete/conv-attr conv)))))))

(comment
  ;; Manual testing helpers

  ;; Backup all projects
  (pdb/backup-all-projects)

  ;; Backup system DB
  (pdb/backup-system-db)

  ;; Check a specific project
  (pdb/get-project :craft-beer)

  ;; Update schema for one project
  (pdb/update-project-for-schema! :craft-beer)

  ;; Update all projects
  (pdb/update-all-projects-for-schema!))