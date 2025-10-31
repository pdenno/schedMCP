(ns sched-mcp.tools.surrogate.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sched-mcp.tools.surrogate.tool :as sur]
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.sutil :as sutil]))

;;; Test fixtures and mock data

;; Mock the database connections
(defn setup-mock-databases []
  ;; Initialize the project database atom to prevent connection attempts
  (swap! sutil/databases-atm assoc :proj-123 {:mock "project-db"})
  (swap! sutil/databases-atm assoc :craft-beer-surrogate-123 {:mock "surrogate-project-db"}))

(defn teardown-mock-databases []
  ;; Clean up the mock databases
  (swap! sutil/databases-atm dissoc :proj-123 :craft-beer-surrogate-123))

(use-fixtures :each (fn [f]
                      (setup-mock-databases)
                      (f)
                      (teardown-mock-databases)))

(def mock-start-result
  {:status "success"
   :message "🟠 Started surrogate expert for craft-beer domain"
   :project-id "craft-beer-surrogate-123"
   :expert-id "expert-456"
   :domain :craft-beer})

(def mock-answer-result
  {:response "🟠 We use a 3-vessel brewing system with separate mash tun, lauter tun, and kettle. Our fermentation typically takes 10-14 days at controlled temperatures."
   :project-id "craft-beer-surrogate-123"})

;;; Tests for start-surrogate tool

(deftest start-surrogate-tool-tests
  (let [tool-config {:tool-type :start-surrogate}]

    (testing "tool creation"
      (is (= :start-surrogate (:tool-type tool-config))))

    (testing "tool-name"
      (is (= "sur_start_expert" (tool-system/tool-name tool-config))))

    (testing "tool-description"
      (let [desc (tool-system/tool-description tool-config)]
        (is (string? desc))
        (is (pos? (count desc)))
        (is (re-find #"surrogate expert agent" desc))
        (is (re-find #"domain expert" desc))))

    (testing "tool-schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (= "object" (:type schema)))
        (is (contains? (:properties schema) :domain))
        (is (contains? (:properties schema) :company_name))
        (is (contains? (:properties schema) :project_name))
        (is (= ["domain"] (:required schema)))))

    (testing "validate-inputs with valid domain"
      (is (= {:domain "craft-beer"}
             (tool-system/validate-inputs
              tool-config
              {:domain "craft-beer"})))

      (is (= {:domain "plate-glass"
              :company-name "Test Company"
              :project-name "Test Project"}
             (tool-system/validate-inputs
              tool-config
              {:domain "plate-glass"
               :company-name "Test Company"
               :project-name "Test Project"}))))

    (testing "validate-inputs with missing domain"
      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs tool-config {})))

      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs
                    tool-config
                    {:company-name "Test"}))))

    (testing "execute-tool with valid domain"
      (with-redefs [suru/start-surrogate-interview
                    (fn [{:keys [domain company-name project-name]}]
                      (merge mock-start-result
                             {:domain domain
                              :company-name company-name
                              :project-name project-name}))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "craft-beer"
                       :company_name "Mountain Peak Brewery"
                       :project_name "Craft Beer Scheduling"})]
          (is (map? result))
          (is (= "success" (:status result)))
          (is (= "craft-beer-surrogate-123" (:project_id result)))
          (is (= "expert-456" (:expert_id result)))
          (is (= "craft-beer" (:domain result))))))

    (testing "execute-tool without optional parameters"
      (with-redefs [suru/start-surrogate-interview
                    (constantly mock-start-result)]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "plate-glass"})]
          (is (map? result))
          (is (= "success" (:status result))))))

    (testing "execute-tool with error"
      (with-redefs [suru/start-surrogate-interview
                    (fn [& _] (throw (Exception. "Failed to start expert")))]

        (let [result (tool-system/execute-tool
                      tool-config
                      {:domain "craft-beer"})]
          (is (map? result))
          (is (= "error" (:status result)))
          (is (re-find #"Failed to start expert" (:message result))))))

    (testing "format-results with success"
      (let [success-result {:status "success"
                            :message "Started expert"
                            :project_id "craft-beer-surrogate-123"
                            :expert_id "expert-456"
                            :domain "craft-beer"}]

        (let [formatted (tool-system/format-results tool-config success-result)]
          (is (false? (:error formatted)))
          (is (vector? (:result formatted)))
          (is (= 1 (count (:result formatted))))
          (is (re-find #"Started surrogate expert for craft-beer"
                       (first (:result formatted))))
          (is (re-find #"Project ID: craft-beer-surrogate-123"
                       (first (:result formatted))))
          (is (re-find #"Expert ID: expert-456"
                       (first (:result formatted)))))))

    (testing "format-results with error"
      (let [error-result {:status "error"
                          :message "Failed to initialize expert"}
            formatted (tool-system/format-results tool-config error-result)]
          (is (true? (:error formatted)))
          (is (= ["Failed to initialize expert"] (:result formatted)))))))

;;; This will probably need to change when I implement ru/text-to-var.
(deftest separate-table-test
  (testing "Whether tables are separated correctly."
    (let [test-table-text
          (str "\"Here are my estimates of the step durations. Note that I added 'Quality Check' which I think should be included in this context:\"\n\n"
               "#+begin_src HTML\n"
               "<table>\n"
               "  <tr><th>Process Step</th>                <th>Duration</th></tr>\n"
               "  <tr><td>Mashing</td>                     <td>90 minutes</td></tr>\n"
               "  <tr><td>Boiling</td>                     <td>60 minutes</td></tr>\n"
               "  <tr><td>Quality Check</td>               <td>15 minutes</td></tr>\n"
               "</table>\n"
               "#+end_src")]
    (is (= {:full-text
            "\"Here are my estimates of the step durations. Note that I added 'Quality Check' which I think should be included in this context:\"\n\n#+begin_src HTML\n<table>\n  <tr><th>Process Step</th>                <th>Duration</th></tr>\n  <tr><td>Mashing</td>                     <td>90 minutes</td></tr>\n  <tr><td>Boiling</td>                     <td>60 minutes</td></tr>\n  <tr><td>Quality Check</td>               <td>15 minutes</td></tr>\n</table>\n#+end_src",
            :table
            {:table-headings [{:title "Process Step", :key :process-step} {:title "Duration", :key :duration}],
             :table-body
             [{:process-step "Mashing", :duration "90 minutes"}
              {:process-step "Boiling", :duration "60 minutes"}
              {:process-step "Quality Check", :duration "15 minutes"}]}}
           (suru/separate-table test-table-text))))))
