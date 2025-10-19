(ns sched-mcp.interviewing.domain.process.job-shop-u
  "(1) Define the a discovery schema for job-shop scheduling problems where each job potentially follows a unique process.
   (2) Define well-formedness constraints for this structure. These can also be used to check the structures produced by the interviewer."
  (:require
   [clojure.pprint                 :refer [cl-format pprint]]
   [clojure.spec.alpha             :as s]
   [mount.core                     :as mount :refer [defstate]]
   [sched-mcp.interviewing.ds-util :as dsu]
   [sched-mcp.project-db           :as pdb]
   [sched-mcp.system-db            :as sdb]
   [sched-mcp.util                 :as util :refer [alog!]]))

(s/def :job-shop-u/DS-message (s/keys :req-un [::message-type ::interview-objective ::interviewer-agent ::DS]))
(s/def ::message-type #(= % :DS-INSTRUCTIONS))
(s/def ::interview-objective string?)
(s/def ::interviewer-agent #(= % :process))

(s/def ::comment string?) ; About annotations

(s/def ::DS (s/keys :req-un [::unit-processes]
                      :opt-un [::msg-id ::DS-ref ::DS-id]))
(s/def ::DS-id #(= % :process/job-shop--unique))
(s/def ::unit-processes (s/coll-of ::process :kind vector?))

;;; --------------------------------- This is all borrowed from flow_shop.clj
(s/def ::process (s/keys :req-un [::process-id] :opt-un [::duration ::inputs ::outputs ::resources]))
(s/def ::process-id (s/or :normal :process-id/val :annotated ::annotated-process-id))
(s/def ::annotated-process-id (s/keys :req-un [:process-id/val ::comment]))
(s/def :process-id/val string?)

(s/def ::thing (s/or :normal :thing/val :quantified ::thing-with-quantity :originated ::thing-with-origin))
(s/def ::thing-with-quantity (s/keys :req-un [::item-id ::quantity]))
(s/def ::thing-with-origin (s/keys :req-un [::item-id ::from]))
(s/def :thing/val string?)

(s/def ::item-id (s/or :normal :item-id/val :annotated ::annotated-item-id))
(s/def ::annotated-item-id (s/keys :req-un [:item-id/val ::comment]))
(s/def :item-id/val string?)

(s/def ::quantity (s/or :normal :quantity/val :annotated ::annotated-quantity))
(s/def :quantity/val (s/or :normal (s/keys :req-un [::units ::value-string]) :annotated ::annotated-quantity))
(s/def ::units (s/or :normal :units/val  :annotated ::annotated-units))
(s/def ::annotated-quantity (s/keys :req-un [:units/val ::comment]))
(s/def :units/val string?)

(s/def ::units (s/or :normal :units/val :annotated ::annotated-units))
(s/def :units/val string?)
(s/def ::annotated-units (s/keys :req-un [:units/val ::comment]))
(s/def ::value-string (s/or :normal :value-string/val :annotated ::annotated-value-string))
(s/def ::annotated-value-string (s/keys :req-un [:value-string/val ::comment]))
(s/def :value-string/val string?)

(s/def ::inputs (s/or :normal :inputs/val :annotated ::annotated-inputs))
(s/def :inputs/val (s/coll-of :input/val :kind vector?))
(s/def :input/val  (s/or :simple ::thing :with-origin ::input-with-origin))
(s/def ::input-with-origin (s/keys :req-un [::item-id ::from]))
(s/def ::annotated-inputs (s/keys :req-un [:inputs/val ::comment]))
(s/def ::from (s/or :normal :from/val :annotated ::annotated-from))
(s/def :from/val string?)
(s/def ::annotated-from (s/keys :req-un [:from/val ::comment]))

(s/def ::outputs (s/or :normal :outputs/val :annotated ::annotated-outputs))
(s/def :outputs/val (s/coll-of :input/val :kind vector?))
(s/def :input/val  ::thing)
(s/def ::annotated-outputs (s/keys :req-un [:outputs/val ::comment]))

(s/def ::resources (s/or :normal :resources/val :annotated ::annotated-resources))
(s/def :resources/val (s/coll-of string? :kind vector?))
(s/def ::annotated-resources (s/keys :req-un [:resources/val ::comment]))

(s/def ::item-id string?)

;;; (s/explain :job-shop-u/DS-message jshopu/job-shop-u)
(def job-shop-u
  "A pprinted (JSON?) version of this is what we'll provide to the interviewer at the start of a job-shop-u problem."
  {:message-type :DS-INSTRUCTIONS
   :budget-decrement 0.10
   :interviewer-agent :process
   :interview-objective (str "These DS-INSTRUCTIONS assumes the interviewees' production operates as a 'true' job shop -- an arrangement where possibly every job has a unique process plan.\n"
                             "The purpose of these DS-INSTRUCTIONS are to describe unit processes of jobs, their inputs, outputs, resources, and (sometimes) typical duration.\n"
                             "A unit process (you might define this term in your interview) is a a fundamental step in the production chain where a specific transformation\n" ; ToDo Could use a link to 'unit process'.
                             "or change is applied to a material or component.\n"
                             "The property unit-processes is a list of all processes objects the interviewees deem relevant to production scheduling.\n"
                             "These process objects are similar to those used in other DS, such as process/flow-shop, except that they do not have a subprocesses property.\n"
                             "The process objects will be referenced in the definition of the job's process plan,\n"
                             "but the interview association with this DS does not capture that relation between the job and the processes.\n"
                             "\n"
                             "The examples in the DS are from an automotive machine shop, a quintessential example of a job shop.\n"
                             "We only include two unit processes in the example, but a typical machine shop might have about 50.")
   :DS
   {:DS-id :process/job-shop--unique
    :unit-processes [{:process-id {:val "hone-cylinder-bores",
                                   :comment (str "Name processes as you see fit, but make sure there are no duplicate uses of the name as process-id.\n"
                                                 "Though there are subprocesses to honing cylinder bores (e.g. fixturing engine block, selecting honing tools, honing, inspection)\n"
                                                 "the interviewees deemed honing cylinder bores a unit process. This is quite reasonable.")}
                      :inputs {:val ["engine block"]
                               :comment "'inputs' is a list of all the raw materials used to make the product."}
                      :outputs {:val [{:item-id "honed engine block",
                                       :quantity {:units "unit" :value-string "1"}}]
                                :comment (str "Inputs and outputs can either be simple strings like we used with 'engine block', or objects like this, with an 'item-id' and 'quantity'.\n"
                                              "Use disgression (mindful of the questioning budget) about where you ask for quantities. Start simple and pursue details were the budget allows.")}
                      :resources {:val ["honing machine", "honing tool", "fixture"],
                                  :comment "Resources, unlike inputs, are durable and reusable. Do not ask about quantities of resources; that's a conversation for another interviewer."},
                      :duration {:val {:units "hours", :value-string "2"},
                                 :comment (str "We use a string for 'value-string' in case interviewees answer it something like 'it varies'.\n"
                                               "You might use a comment to describe the context of this value.\n"
                                               "For example, you could ask the interviewees about different durations for honing a straight 4 cylinder block versus a V8 and use their response\n"
                                               "in a comment to elaborate important considerations about the unit process's duration.")}},

                     {:process-id "aluminum-cylinder-head-resurfacing"
                      :inputs ["cylinder head"]
                      :outputs ["flat cylinder head"]
                      :resources {:val ["milling machine"]
                                  :comment "We might have learned in the interview that they use a milling machine for resurfacing aluminum heads and a grinding machine for cast iron heads."}
                      :duration {:val {:units "hours", :value-string "2"},
                                 :comment "The value here does not include leak testing, which is treated as a separate unit process that usually follows resurfacing."}}]}})

;;; See if it compiles.
(when-not (s/valid? :job-shop-u/DS-message job-shop-u)
  (throw (ex-info "Invalid DS (job-shop--unique)" {})))

(defn completeness-test [_ds] true)

(defmethod dsu/ds-valid? :process/job-shop--unique
  [tag obj]
  (or (s/valid? ::DS obj)
      (alog! (str "Invalid DS" tag " " (with-out-str (pprint obj))))))

;;; ------------------------------- checking for completeness ---------------
(defmethod dsu/ds-complete? :process/job-shop--unique
  [tag pid]
  (let [ds (-> (pdb/get-ASCR pid tag) dsu/strip-annotations)
        complete? (completeness-test ds)]
    (alog! (cl-format nil "{:log-comment \"This is the ASCR for ~A  (complete? =  ~A):~%~S\"}"
                      tag complete? (with-out-str (pprint ds)))
           {:console? true #_#_:elide-console 130})
    complete?))

;;; -------------------- Starting and stopping -------------------------
(defn init-job-shop-u
  []
  (if (s/valid? :job-shop-u/DS-message job-shop-u)
    (when-not (sdb/same-DS-instructions? job-shop-u)
      ;(sutil/update-resources-DS-json! job-shop-u)
      (sdb/put-DS-instructions! job-shop-u))
    (throw (ex-info "Invalid DS message (job-shop-u)." {}))))

(defstate job-shop-u-ds
  :start (init-job-shop-u))
