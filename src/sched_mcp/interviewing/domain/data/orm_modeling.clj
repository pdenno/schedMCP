(ns sched-mcp.interviewing.domain.data.orm-modeling
  "Discovery Schema for Object-Role Modeling (ORM) of a single area of inquiry.
   This DS focuses on defining objects and fact types for ONE area at a time."
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [mount.core :as mount :refer [defstate]]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.sutil :as sutil :refer [clj2json-pretty]]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :as util :refer [alog! log!]]))

(def ^:diag diag (atom nil))

;;; ============================== Specs ==============================

(s/def :orm-modeling/DS-message (s/keys :req-un [::message-type ::interview-objective ::interviewer-agent ::DS]))
(s/def ::message-type #(= % :DS-INSTRUCTIONS))
(s/def ::interview-objective string?)
(s/def ::interviewer-agent #(= % :data))
(s/def ::comment string?)

(s/def ::DS (s/keys :req-un [::focus-area ::inquiry-area-objects ::fact-types]
                    :opt-un [::msg-id ::DS-ref ::DS-id]))
(s/def ::DS-id #(= % :data/orm-modeling))

(s/def ::focus-area (s/or :normal :focus-area/val :annotated ::annotated-focus-area))
(s/def :focus-area/val string?)
(s/def ::annotated-focus-area (s/keys :req-un [:focus-area/val ::comment]))

(s/def ::inquiry-area-objects (s/or :normal :inquiry-area-objects/val :annotated ::annotated-inquiry-area-objects))
(s/def :inquiry-area-objects/val (s/coll-of ::inquiry-area-object :kind vector?))
(s/def ::inquiry-area-object (s/keys :req-un [::definition ::object-id]))
(s/def ::annotated-inquiry-area-objects (s/keys :req-un [::comment :inquiry-area-objects/val]))

(s/def ::object-id (s/or :normal :object-id/val :annotated ::annotated-object-id))
(s/def :object-id/val string?)
(s/def ::annotated-object-id (s/keys :req-un [::comment :object-id/val]))

(s/def ::definition (s/or :normal :definition/val :annotated ::annotated-definition))
(s/def :definition/val string?)
(s/def ::annotated-definition (s/keys :req-un [::comment :definition/val]))

(s/def ::fact-types (s/or :normal :fact-types/val :annotated ::annotated-fact-types))
(s/def :fact-types/val (s/coll-of ::fact-type :kind vector?))
(s/def ::fact-type (s/keys :req-un [::fact-type-id ::objects ::reference-modes ::uniqueness]
                           :opt-un [::mandatory?]))
(s/def ::annotated-fact-types (s/keys :req-un [::comment :fact-types/val]))

(s/def ::fact-type-id (s/or :normal :fact-type-id/val :annotated ::annotated-fact-type-id))
(s/def :fact-type-id/val string?)
(s/def ::annotated-fact-type-id (s/keys :req-un [::comment :fact-type-id/val]))

(s/def ::objects (s/or :normal :objects/val :annotated ::annotated-objects))
(s/def :objects/val (s/coll-of ::object :kind vector?))
(s/def ::object string?)
(s/def ::annotated-objects (s/keys :req-un [::comment :objects/val]))

(s/def ::reference-modes (s/or :normal :reference-modes/val :annotated ::annotated-reference-modes))
(s/def :reference-modes/val (s/coll-of ::reference-mode :kind vector?))
(s/def ::reference-mode string?)
(s/def ::annotated-reference-modes (s/keys :req-un [::comment :reference-modes/val]))

(s/def ::uniqueness (s/or :normal :uniqueness/val :annotated ::annotated-uniqueness))
(s/def :uniqueness/val (s/coll-of ::uniquenes :kind vector?))
(s/def ::uniquenes (s/coll-of string? :kind vector?))
(s/def ::annotated-uniqueness (s/keys :req-un [::comment :uniqueness/val]))

(s/def ::mandatory? (s/or :normal :mandatory?/val :annotated ::annotated-mandatory?))
(s/def :mandatory?/val (s/coll-of ::mandatory?-key :kind vector?))
(s/def ::mandatory?-key string?)
(s/def ::annotated-mandatory? (s/keys :req-un [::comment :mandatory?/val]))

;;; ============================== DS Definition ==============================

(def orm-modeling
  {:message-type :DS-INSTRUCTIONS
   :budget-decrement 0.15
   :interviewer-agent :data
   :multiple-instance? true
   :interview-objective
   (str "You are conducting Object-Role Modeling (ORM) for a SINGLE area of inquiry.\n"
        "The focus-area property tells you which area you are modeling.\n"
        "\n"
        "Your goal is to discover:\n"
        "  1) The objects (entities) involved in this area\n"
        "  2) The relationships (fact types) among these objects\n"
        "  3) Constraints on these relationships (mandatory, uniqueness)\n"
        "\n"
        "Start by asking what information they track about this area.\n"
        "For example, if focus-area is 'customer-orders', ask:\n"
        "  'What information do you track about customer orders?'\n"
        "\n"
        "From their response, identify the objects and define them in inquiry-area-objects.\n"
        "Then discover the relationships by asking questions like:\n"
        "  'For each order, do you track a promise date?'\n"
        "  'Can one order have multiple products?'\n"
        "\n"
        "ORM ENCODING GUIDE:\n"
        "\n"
        "For an n-ary fact type, use arrays of n elements.\n"
        "Example ternary: 'ACADEMIC obtained DEGREE from UNIVERSITY'\n"
        "\n"
        (clj2json-pretty
         {:fact-type-id "ACADEMIC-obtains-DEGREE-from-UNIVERSITY"
          :objects ["academic" "degree" "university"]
          :reference-modes ["empNr" "code" "code"]
          :mandatory? ["must" "" ""]
          :uniqueness [["key1" "key1" ""]]})
        "\n"
        "The 'objects' property orders the entities in a natural verbalization.\n"
        "The 'reference-modes' are how we identify each object (empNr, code, timepoint, etc.).\n"
        "\n"
        "The 'mandatory?' property indicates if an entity MUST participate:\n"
        "  - empty string \"\" = not mandatory\n"
        "  - \"must\" = alethic constraint (necessity)\n"
        "  - \"should\" = deontic constraint (obligation)\n"
        "\n"
        "The 'uniqueness' property shows functional dependencies.\n"
        (clj2json-pretty ["key1" "key1" ""]) " means [academic, degree] determines university.\n"
        "If there's another uniqueness constraint, add another array:\n"
        (clj2json-pretty ["key2" "" "key2"]) " means [academic, university] determines degree.\n"
        "\n"
        "IMPORTANT: Focus only on the current focus-area. Don't ask about other areas.\n"
        "The orchestrator will call you again for each area that needs modeling.\n")
   :DS {:DS-id :data/orm-modeling
        :focus-area
        {:comment "This tells you which area of inquiry you are modeling. Use this to guide your questions."
         :val "customer-orders"}

        :inquiry-area-objects
        {:comment
         (str "Define the entities (objects) relevant to this area of inquiry.\n"
              "Each object needs an object-id and a definition.\n"
              "Object-ids should be simple, descriptive names (e.g., 'order', 'product', 'customer').\n"
              "Definitions explain what the object represents in their domain.")
         :val [{:object-id "order"
                :definition "a unique identifier for a customer order"}
               {:object-id "product"
                :definition "a type of product that can be ordered"}
               {:object-id "customer"
                :definition "the person or organization placing the order"}
               {:object-id "promise-date"
                :definition "the date by which delivery is promised"}
               {:object-id "quantity"
                :definition "an amount of product"}]}

        :fact-types
        {:comment
         (str "Define the relationships (fact types) among the objects.\n"
              "Each fact type has:\n"
              "  - fact-type-id: descriptive name (e.g., 'ORDER-has-PROMISE-DATE')\n"
              "  - objects: the entities involved (from inquiry-area-objects)\n"
              "  - reference-modes: how we identify each object\n"
              "  - mandatory?: which objects must participate\n"
              "  - uniqueness: functional dependencies\n")
         :val [{:fact-type-id "ORDER-has-PROMISE-DATE"
                :objects ["order" "promise-date"]
                :reference-modes ["order-number" "timepoint"]
                :mandatory? {:val ["must" ""]
                             :comment "Every order must have a promise date"}
                :uniqueness {:val [["key1" ""]]
                             :comment "Each order has exactly one promise date"}}

               {:fact-type-id "ORDER-has-PRODUCT-QUANTITY"
                :objects ["order" "product" "quantity"]
                :reference-modes ["order-number" "product-code" "amount"]
                :mandatory? ["must" "" ""]
                :uniqueness [["key1" "key1" ""]]
                :comment "One order can have multiple products with quantities"}

               {:fact-type-id "ORDER-is-for-CUSTOMER"
                :objects ["order" "customer"]
                :reference-modes ["order-number" "customer-id"]
                :mandatory? ["must" ""]
                :uniqueness [["key1" ""]]}]}}})

;;; Validate at load time
(when-not (s/valid? :orm-modeling/DS-message orm-modeling)
  (throw (ex-info "Invalid DS (orm-modeling)" {})))

;;; ============================== DS Util Methods ==============================

(defmethod dsu/ds-valid? :data/orm-modeling
  [tag obj]
  (or (s/valid? ::DS obj)
      (alog! (str "Invalid DS " tag " " (with-out-str (pprint obj))))))

(defn combine
  "Merge new fact-types into existing ones, replacing by fact-type-id."
  [best more]
  (let [best-fact-ids (dsu/collect-keys-vals best :fact-type-id)
        more-fact-ids (dsu/collect-keys-vals more :fact-type-id)
        fact-inserts (set/difference more-fact-ids best-fact-ids)
        fact-replaces (set/intersection best-fact-ids more-fact-ids)]
    (as-> best ?b
      (reduce (fn [r new-id]
                (dsu/insert-by-id r :fact-types (dsu/get-object more :fact-type-id new-id)))
              ?b
              fact-inserts)
      (reduce (fn [r new-id]
                (dsu/replace-by-id r :fact-types :fact-type-id (dsu/get-object more :fact-type-id new-id)))
              ?b
              fact-replaces))))

(defmethod dsu/ds-combine :data/orm-modeling
  [_tag scr ascr]
  (log! :info (str "ORM-modeling ds-combine called"))
  (log! :info (str "  SCR keys: " (keys scr)))
  (log! :info (str "  ASCR keys before: " (keys ascr)))
  (let [stripped-scr (dsu/strip-annotations scr)
        _ (log! :info (str "  Stripped SCR keys: " (keys stripped-scr)))
        result (if (empty? ascr)
                 stripped-scr
                 (combine ascr stripped-scr))]
    (log! :info (str "  ASCR keys after: " (keys result)))
    result))

(defn completeness-test
  "ORM modeling is complete when we have both objects and fact-types defined."
  [ascr]
  (and (seq (:inquiry-area-objects ascr))
       (seq (:fact-types ascr))))

(defmethod dsu/ds-complete? :data/orm-modeling
  [_tag ascr]
  (let [stripped-ascr (dsu/strip-annotations ascr)]
    (completeness-test stripped-ascr)))

;;; ============================== Initialization ==============================

(defn init-orm-modeling
  []
  (if (s/valid? :orm-modeling/DS-message orm-modeling)
    (when-not (sdb/same-DS-instructions? orm-modeling)
      (log! :info "Updating orm-modeling DS in system DB.")
      (sdb/put-DS-instructions! orm-modeling))
    (throw (ex-info "Invalid DS message (orm-modeling)." {}))))

(defstate orm-modeling-ds
  :start (init-orm-modeling))
