(ns sched-mcp.interviewing.domain.data.examples
  "Discovery Schema for creating example data tables to verify fact types with users.
   This DS creates tables with column headings and sample rows."
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [mount.core :as mount :refer [defstate]]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :as util :refer [alog! log!]]))

(def ^:diag diag (atom nil))

;;; ============================== Specs ==============================

(s/def :examples/DS-message (s/keys :req-un [::message-type ::interview-objective ::interviewer-agent ::DS]))
(s/def ::message-type #(= % :DS-INSTRUCTIONS))
(s/def ::interview-objective string?)
(s/def ::interviewer-agent #(= % :data))
(s/def ::comment string?)

(s/def ::DS (s/keys :req-un [::focus-fact-types ::examples-map]
                    :opt-un [::msg-id ::DS-ref ::DS-id]))
(s/def ::DS-id #(= % :data/examples))

(s/def ::focus-fact-types (s/or :normal :focus-fact-types/val :annotated ::annotated-focus-fact-types))
(s/def :focus-fact-types/val (s/coll-of string? :kind vector?))
(s/def ::annotated-focus-fact-types (s/keys :req-un [:focus-fact-types/val ::comment]))

(s/def ::examples-map (s/or :normal :examples-map/val :annotated ::annotated-examples-map))
(s/def :examples-map/val (s/map-of string? ::example-table))
(s/def ::annotated-examples-map (s/keys :req-un [:examples-map/val ::comment]))

(s/def ::example-table (s/keys :req-un [::column-headings ::rows]))

(s/def ::column-headings (s/or :normal :column-headings/val :annotated ::annotated-column-headings))
(s/def :column-headings/val (s/coll-of ::column-heading :kind vector?))
(s/def ::column-heading string?)
(s/def ::annotated-column-headings (s/keys :req-un [::comment :column-headings/val]))

(s/def ::rows (s/or :normal :rows/val :annotated ::annotated-rows))
(s/def :rows/val (s/coll-of ::row :kind vector?))
(s/def ::row (s/coll-of string? :kind vector?))
(s/def ::annotated-rows (s/keys :req-un [::comment :rows/val]))

;;; ============================== DS Definition ==============================

(def examples
  {:message-type :DS-INSTRUCTIONS
   :budget-decrement 0.08
   :interviewer-agent :data
   :interview-objective
   (str "You are creating example data tables to verify fact types with the interviewee.\n"
        "The focus-fact-types property lists which fact-types need example tables.\n"
        "\n"
        "For each fact-type, create a table showing realistic example data.\n"
        "Present the table in HTML format and ask the interviewee to verify and edit if needed.\n"
        "\n"
        "IMPORTANT: You can include HTML tables in questions using this format:\n"
        "\n"
        "#+begin_src HTML\n"
        "<table>\n"
        "  <tr><th>Column 1</th><th>Column 2</th></tr>\n"
        "  <tr><td>Value 1</td><td>Value 2</td></tr>\n"
        "</table>\n"
        "#+end_src\n"
        "\n"
        "The UI can render these tables and allow the interviewee to edit cells and add/remove rows.\n"
        "\n"
        "GUIDELINES FOR CREATING TABLES:\n"
        "\n"
        "1) Column headings should match the reference-modes or object names from the fact-type\n"
        "2) Create 5-10 realistic example rows\n"
        "3) Use data that reflects their actual domain (not generic examples)\n"
        "4) Show variety in the data (different values, not all the same)\n"
        "5) Respect the constraints (mandatory, uniqueness) from the fact-type definition\n"
        "\n"
        "Example question:\n"
        "  'Does the following table capture the employee skill certification information we discussed?\n"
        "   Feel free to edit any cells or add/remove rows.'\n"
        "\n"
        "   #+begin_src HTML\n"
        "   <table>\n"
        "     <tr><th>Employee No.</th><th>Skill</th><th>Certification Date</th></tr>\n"
        "     <tr><td>EN-123</td><td>Milling Centers</td><td>2024-10-05</td></tr>\n"
        "     <tr><td>EN-098</td><td>Milling Centers</td><td>2022-11-13</td></tr>\n"
        "   </table>\n"
        "   #+end_src\n"
        "\n"
        "Extract the verified table data (with any edits they made) into the examples-map.\n"
        "The key should be the fact-type-id, the value should be {column-headings: [...], rows: [[...]]}.\n")
   :DS {:DS-id :data/examples
        :focus-fact-types
        {:comment
         (str "This property lists the fact-type-ids that need example tables.\n"
              "The orchestrator will set this based on which fact-types don't yet have examples.")
         :val ["ORDER-has-PROMISE-DATE" "ORDER-has-PRODUCT-QUANTITY"]}

        :examples-map
        {:comment
         (str "Map from fact-type-id to example table.\n"
              "Each table has column-headings (array of strings) and rows (array of arrays of strings).")
         :val {"ORDER-has-PROMISE-DATE"
               {:column-headings ["order-number" "promise-date"]
                :rows [["CO-865204" "2025-11-06"]
                       ["CO-863393" "2025-11-13"]
                       ["CO-865534" "2025-03-28"]
                       ["CO-912847" "2025-11-20"]
                       ["CO-834521" "2025-12-01"]]}

               "ORDER-has-PRODUCT-QUANTITY"
               {:column-headings ["order-number" "product-code" "quantity"]
                :rows [["CO-865204" "PN-38553" "1 unit"]
                       ["CO-863393" "PN-37454" "7 units"]
                       ["CO-865534" "PN-73853" "2 family packs"]
                       ["CO-912847" "PN-38553" "3 units"]
                       ["CO-834521" "PN-91234" "5 units"]]}}}}})

;;; Validate at load time
(when-not (s/valid? :examples/DS-message examples)
  (throw (ex-info "Invalid DS (examples)" {})))

;;; ============================== DS Util Methods ==============================

(defmethod dsu/ds-valid? :data/examples
  [tag obj]
  (or (s/valid? ::DS obj)
      (alog! (str "Invalid DS " tag " " (with-out-str (pprint obj))))))

(defn combine
  "Merge new examples into existing ones, replacing by fact-type-id key."
  [best more]
  (merge best more))

(defmethod dsu/ds-combine :data/examples
  [_tag scr ascr]
  (log! :info (str "Examples ds-combine called"))
  (log! :info (str "  SCR keys: " (keys scr)))
  (log! :info (str "  ASCR keys before: " (keys ascr)))
  (let [stripped-scr (dsu/strip-annotations scr)
        _ (log! :info (str "  Stripped SCR keys: " (keys stripped-scr)))
        ;; Merge the examples-map
        result (if (empty? ascr)
                 stripped-scr
                 (update ascr :examples-map merge (:examples-map stripped-scr)))]
    (log! :info (str "  ASCR keys after: " (keys result)))
    (log! :info (str "  Examples count: " (count (:examples-map result))))
    result))

(defn completeness-test
  "Examples are complete when all focus-fact-types have entries in examples-map."
  [ascr]
  (let [focus-types (set (:focus-fact-types ascr))
        examples-keys (set (keys (:examples-map ascr)))]
    (set/subset? focus-types examples-keys)))

(defmethod dsu/ds-complete? :data/examples
  [_tag ascr]
  (let [stripped-ascr (dsu/strip-annotations ascr)]
    (completeness-test stripped-ascr)))

;;; ============================== Initialization ==============================

(defn init-examples
  []
  (if (s/valid? :examples/DS-message examples)
    (when-not (sdb/same-DS-instructions? examples)
      (log! :info "Updating examples DS in system DB.")
      (sdb/put-DS-instructions! examples))
    (throw (ex-info "Invalid DS message (examples)." {}))))

(defstate examples-ds
  :start (init-examples))
