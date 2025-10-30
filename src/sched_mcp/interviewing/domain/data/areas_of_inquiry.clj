(ns sched-mcp.interviewing.domain.data.areas-of-inquiry
  "Discovery Schema for identifying areas of inquiry (kinds of data) the interviewees use.
   This is a simple warm-up DS that extracts area names from the expert's response."
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.spec.alpha :as s]
   [mount.core :as mount :refer [defstate]]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.system-db :as sdb]
   [sched-mcp.util :as util :refer [alog!]]))

(def ^:diag diag (atom nil))

;;; ============================== Specs ==============================

(s/def :areas-of-inquiry/DS-message (s/keys :req-un [::message-type ::interview-objective ::interviewer-agent ::DS]))
(s/def ::DS (s/keys :req-un [::areas]
                    :opt-un [::msg-id ::DS-ref ::DS-id]))
(s/def ::DS-id #(= % :data/areas-of-inquiry))
(s/def ::message-type #(= % :DS-INSTRUCTIONS))
(s/def ::interview-objective string?)
(s/def ::interviewer-agent #(= % :data))
(s/def ::comment string?)

(s/def ::areas (s/or :normal :areas/val :annotated ::annotated-areas))
(s/def :areas/val (s/coll-of string? :kind vector?))
(s/def ::annotated-areas (s/keys :req-un [:areas/val ::comment]))

;;; ============================== DS Definition ==============================

(def areas-of-inquiry
  {:message-type :DS-INSTRUCTIONS
   :budget-decrement 0.05
   :interviewer-agent :data
   :interview-objective
   (str "You are discovering what kinds of data the interviewees use in their work.\n"
        "This is a warm-up question to enumerate high-level areas of inquiry.\n"
        "\n"
        "Ask this question verbatim:\n"
        "\n"
        "  'To get started, could you list the kinds of data that you use to schedule production?\n"
        "   For example, do you have spreadsheets containing customer orders, raw material delivery,\n"
        "   process plans, materials on hand, task durations, worker skills, etc.?'\n"
        "\n"
        "Extract area names directly from their response. Use their terminology, not predefined categories.\n"
        "For example, if they mention 'tank availability', use 'tank-availability' not 'equipment'.\n"
        "Create area names that are descriptive and match what they actually said.\n"
        "\n"
        "If they provide a bulleted list with labels, use those labels.\n"
        "If they provide narrative text, extract the key data categories they mention.\n")
   :DS {:DS-id :data/areas-of-inquiry
        :areas
        {:comment
         (str "This property contains the names of areas of inquiry extracted from the interviewee's response.\n"
              "These should be descriptive names based on what the interviewee actually said.\n"
              "\n"
              "For example, if the interviewee says:\n"
              "  'We track customer orders, raw materials on hand, equipment schedules, and worker certifications'\n"
              "Then a good value would be:\n"
              "  [\"customer-orders\" \"raw-materials-on-hand\" \"equipment-schedules\" \"worker-certifications\"]\n"
              "\n"
              "DO NOT use generic categories like 'data' or 'information'.\n"
              "DO use hyphenated lowercase names (e.g., 'tank-availability' not 'Tank Availability').\n"
              "DO preserve the interviewee's terminology (e.g., if they say 'reorder points', use 'reorder-points').\n")
         :val ["customer-orders" "materials-on-hand" "equipment-schedules"]}}})

;;; Validate at load time
(when-not (s/valid? :areas-of-inquiry/DS-message areas-of-inquiry)
  (throw (ex-info "Invalid DS (areas-of-inquiry)" {})))

;;; ============================== DS Util Methods ==============================

(defmethod dsu/ds-valid? :data/areas-of-inquiry
  [tag obj]
  (or (s/valid? ::DS obj)
      (alog! (str "Invalid DS " tag " " (with-out-str (pprint obj))))))

(defn completeness-test
  "Check if the ASCR contains the areas property with at least one area."
  [ascr]
  (and (contains? ascr :areas)
       (seq (:areas ascr))))

(defmethod dsu/ds-combine :data/areas-of-inquiry
  [_tag scr ascr]
  (let [stripped-scr (dsu/strip-annotations scr)]
    (if (empty? ascr)
      stripped-scr
      (update ascr :areas
              (fn [existing-areas]
                (vec (distinct (concat existing-areas (:areas stripped-scr)))))))))

(defmethod dsu/ds-complete? :data/areas-of-inquiry
  [_tag ascr]
  (let [stripped-ascr (dsu/strip-annotations ascr)]
    (completeness-test stripped-ascr)))

;;; ============================== Initialization ==============================

(defn init-areas-of-inquiry
  []
  (if (s/valid? :areas-of-inquiry/DS-message areas-of-inquiry)
    (when-not (sdb/same-DS-instructions? areas-of-inquiry)
      (alog! "Updating areas-of-inquiry DS in system DB.")
      (sdb/put-DS-instructions! areas-of-inquiry))
    (throw (ex-info "Invalid DS message (areas-of-inquiry)." {}))))

(defstate areas-of-inquiry-ds
  :start (init-areas-of-inquiry))
