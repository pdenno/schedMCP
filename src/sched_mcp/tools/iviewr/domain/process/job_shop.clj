(ns sched-mcp.iviewr.domain.process.job-shop
  "(1) Define a discovery schema for a job-shop scheduling problem.
       As the case is with job-shop problems, this structure defines work to be performed in typical job.
   (2) Define well-formedness constraints for this structure. These can also be used to check the structures produced by the interviewer.

   Note: We don't load this code at system startup. When you compile it, it writes the EADS to resources/EADS/job-shop.txt"
  (:require
   [clojure.pprint                 :refer [cl-format pprint]]
   [clojure.set                    :as set]
   [clojure.spec.alpha             :as s]
   [mount.core                     :as mount :refer [defstate]]
   [sched-mcp.tools.orch.ds-util   :as dsu :refer [ds-complete? ds-valid? combine-ds!]]
   [sched-mcp.project-db           :as pdb]
   [sched-mcp.sutil                :as sutil :refer [clj2json-pretty]]
   [sched-mcp.system-db            :as sdb]
   [sched-mcp.util                 :as util :refer [log! alog!]]))

(s/def :job-shop/DS-message (s/keys :req-un [::message-type ::interview-objective ::interviewer-agent ::DS]))
(s/def ::message-type #(= % :DS-INSTRUCTIONS))
(s/def ::interview-objective string?)
(s/def ::interviewer-agent #(= % :process))

(s/def ::comment string?) ; About annotations

(s/def ::DS (s/keys :req-un [::classifiable-jobs?]
                      :opt-un [::msg-id ::DS-ref ::DS-id]))
(s/def ::DS-id #(= % :process/job-shop))
(s/def ::classifiable-jobs? (s/or :normal :classifiable-jobs?/val :annotated ::annotated-classifiable-jobs?))
(s/def :classifiable-jobs?/val boolean?)
(s/def ::annotated-classifiable-jobs? (s/keys :req-un [:classifiable-jobs?/val ::comment]))

;;; (s/valid? ::fshop/DS (:DS fshop/job-shop))
(def job-shop
  "A pprinted (JSON?) version of this is what we'll provide to the interviewer at the start of a job-shop problem."
  {:message-type :DS-INSTRUCTIONS
   :interviewer-agent :process
   :interview-objective (str "These DS-INSTRUCTIONS assumes the interviewees' production processes are organized as a job shop.\n"
                             "We have two separate approaches to model job shop scheduling problems: in one of these, the jobs are classified to match a small (less than a dozen or so)\n"
                             "different process plans; in the other, a process plans will need to be specified for each job.\n"
                             "The purpose of these DS-INSTRUCTIONS is only to determine which of these two subclasses of job shop models should be pursued.\n"
                             "Once this is determined, the orchestrator will likely then choose either DS 'process/job-shop--classifiable' or 'process/job-shop--unique-order' corresponding\n"
                             "respectively to the two separate approaches just described.\n"
                             "The two types are mutually exclusive: if you decide that 'classifiable-jobs?' is true, the orchestator will then pursue DS instructions 'process/job-shop--classifiable'.\n"
                             "This is good, because we don't have good instructions for 'process/job-shop--unique-order' currently!") ; <==================== Temporary!
   :DS
   {:DS-id :process/job-shop
    :classifiable-jobs? {:val true,
                         :comment (str "This property is true only in the case that it seems reasonable to pre-classify jobs as corresponding to a small collection (a dozen or so) process plans.\n"
                                       "If, in contrast, it seems more reasonable for the the firm to define a (possibly unique) production process for each job, classifiable-jobs? should be false.\n"
                                       "It is reasonable to ask whether defining a process plan for each job is part of their workflow.")}}})

;;; See if it compiles.
(when-not (s/valid? :job-shop/DS-message job-shop)
  (throw (ex-info "Invalid DS (job-shop)" {})))

(defmethod ds-valid? :process/job-shop
  [tag obj]
  (or (s/valid? ::DS obj)
      (alog! (str "Invalid DS" tag " " (with-out-str (pprint obj))))))

(defn completeness-test [_ds] true)

(defmethod ds-complete? :process/job-shop
  [tag pid]
  (let [ds (-> (pdb/get-ASCR pid tag) dsu/strip-annotations)
        complete? (completeness-test ds)]
    (alog! (cl-format nil "{:log-comment \"This is the ASCR for ~A  (complete? =  ~A):~%~S\"}"
                      tag complete? (with-out-str (pprint ds)))
           {:console? true #_#_:elide-console 130})
    complete?))

;;; (jshop/init-job-shop)
(defn init-job-shop
  []
  (if (s/valid? :job-shop/DS-message job-shop)
    (when-not (sdb/same-DS-instructions? job-shop)
      ;(sutil/update-resources-DS-json! job-shop)
      (sdb/put-DS-instructions! job-shop))
    (throw (ex-info "Invalid DS message (job-shop)." {}))))

(defstate job-shop-ds
  :start (init-job-shop))
