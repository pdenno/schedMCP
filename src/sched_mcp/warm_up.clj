(ns sched-mcp.warm-up
  "Warm-up interview phase for scheduling challenges"
  (:require
   [datahike.api :as d]
   [sched-mcp.sutil :as sutil :refer [connect-atm]]
   [sched-mcp.util :as util :refer [alog!]]))

(def ^:diag diag (atom nil))

;;; Simplified EADS structure for warm-up phase
(def warm-up-questions
  [{:id :scheduling-challenges
    :text "What are the main scheduling challenges you face in your manufacturing process?"
    :help "Think about bottlenecks, resource conflicts, timing constraints, or coordination issues."
    :required true}

   {:id :product-or-service
    :text "What product or service does your organization provide?"
    :help "This helps us understand your domain and tailor the scheduling solution."
    :required true}

   {:id :one-more-thing
    :text "Is there anything else about your scheduling needs that would be helpful for us to know?"
    :help "Any additional context, constraints, or goals you'd like to share."
    :required false}])

(defn init-warm-up
  "Initialize the warm-up phase data structure"
  [project-id conversation-id]
  (let [conn (connect-atm project-id)
        eads-id (keyword (str "eads-warm-up-" (System/currentTimeMillis)))]
    ;; Store initial EADS structure
    (d/transact conn [{:db/ident eads-id
                       :eads/structure (pr-str {:phase :warm-up
                                                :questions warm-up-questions
                                                :answers {}
                                                :complete? false})}])
    ;; Link to conversation
    (d/transact conn [{:db/id [:conversation/id conversation-id]
                       :eads/data eads-id}])
    {:eads-id eads-id
     :phase :warm-up}))

(defn get-eads-data
  "Get current EADS data for the conversation"
  [project-id conversation-id]
  (let [conn (connect-atm project-id)
        eads-eid (d/q '[:find ?eads .
                        :in $ ?cid
                        :where
                        [?c :conversation/id ?cid]
                        [?c :eads/data ?eads]]
                      @conn conversation-id)]
    (when eads-eid
      (let [eads-str (d/q '[:find ?str .
                            :in $ ?eid
                            :where [?eid :eads/structure ?str]]
                          @conn eads-eid)]
        (when eads-str
          (read-string eads-str))))))

(defn update-eads-data!
  "Update EADS data"
  [project-id conversation-id eads-data]
  (let [conn (connect-atm project-id)
        eads-eid (d/q '[:find ?eads .
                        :in $ ?cid
                        :where
                        [?c :conversation/id ?cid]
                        [?c :eads/data ?eads]]
                      @conn conversation-id)]
    (when eads-eid
      (d/transact conn [{:db/id eads-eid
                         :eads/structure (pr-str eads-data)}]))))

(defn get-next-question
  "Get the next unanswered question"
  [project-id conversation-id]
  (let [eads-data (get-eads-data project-id conversation-id)
        answers (:answers eads-data {})
        questions (:questions eads-data)]
    ;; Find first unanswered required question, or first unanswered optional
    (or (first (filter #(and (:required %)
                             (not (contains? answers (:id %))))
                       questions))
        (first (filter #(not (contains? answers (:id %)))
                       questions)))))

(defn get-progress
  "Get interview progress"
  [project-id conversation-id]
  (let [eads-data (get-eads-data project-id conversation-id)
        answers (:answers eads-data {})
        questions (:questions eads-data)
        required-qs (filter :required questions)
        answered-required (count (filter #(contains? answers (:id %)) required-qs))]
    {:total-questions (count questions)
     :required-questions (count required-qs)
     :answered (count answers)
     :answered-required answered-required
     :complete? (= answered-required (count required-qs))
     :phase :warm-up}))

(defn process-answer
  "Process an answer and update EADS"
  [project-id conversation-id answer question-id]
  (let [eads-data (get-eads-data project-id conversation-id)
        question-id (if (string? question-id)
                      (keyword question-id)
                      question-id)
        updated-answers (assoc (:answers eads-data {}) question-id answer)
        updated-eads (assoc eads-data :answers updated-answers)

        ;; Check if all required questions are answered
        required-qs (filter :required (:questions eads-data))
        all-required-answered? (every? #(contains? updated-answers (:id %)) required-qs)]

    ;; Update EADS
    (update-eads-data! project-id conversation-id
                       (assoc updated-eads :complete? all-required-answered?))

    ;; Return result
    {:success true
     :complete? all-required-answered?
     :progress (get-progress project-id conversation-id)}))
