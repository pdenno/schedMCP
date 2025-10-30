(ns sched-mcp.tools.orch.inspect
  "Project inspection using Malli schemas to detect structural inconsistencies.

   This tool validates project state against expected structure and reports issues.
   It's designed to be queried by the orchestrator to determine what work needs to be done."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]
   [sched-mcp.project-db :as pdb]
   [sched-mcp.util :refer [log!]]))

;;; ============================== Malli Schemas ==============================

(def Message
  "Schema for a conversation message"
  [:map
   [:message/id int?]
   [:message/from [:enum :system :surrogate :human]]
   [:message/content string?]
   [:message/timestamp inst?]
   ;; Optional fields
   [:message/answers-question {:optional true} int?]
   [:message/table {:optional true} string?]])

(def ConversationStatus
  "Valid conversation statuses"
  [:enum :not-started :in-progress :ds-exhausted :complete])

(def Conversation
  "Schema for a conversation.

   INVARIANT: If status is :not-started, messages should be empty or absent"
  [:map
   [:conversation/id keyword?]
   [:conversation/status ConversationStatus]
   [:conversation/messages {:optional true} [:vector Message]]
   [:conversation/active-DS-id {:optional true} keyword?]])

(def Project
  "Schema for a complete project"
  [:map
   [:project/id keyword?]
   [:project/name {:optional true} string?]
   [:project/conversations {:optional true} [:vector Conversation]]
   [:project/active-conversation {:optional true} keyword?]
   [:project/surrogate {:optional true} any?]
   [:project/claims {:optional true} any?]
   [:project/execution-status {:optional true} keyword?]])

;;; ============================== Custom Validators ==============================

(defn validate-conversation-status-consistency
  "Check that conversation status matches its message count.

   Rules:
   - :not-started should have 0 messages (or no :messages key)
   - :in-progress should have at least 1 message
   - :complete or :ds-exhausted should have at least 2 messages (Q&A pairs)"
  [conversation]
  (let [status (:conversation/status conversation)
        messages (:conversation/messages conversation [])
        msg-count (count messages)
        cid (:conversation/id conversation)]
    (case status
      :not-started
      (when (pos? msg-count)
        {:error :status-message-mismatch
         :conversation-id cid
         :status status
         :message-count msg-count
         :message (str "Conversation " cid " has status :not-started but contains "
                       msg-count " messages")})

      :in-progress
      (when (zero? msg-count)
        {:error :status-message-mismatch
         :conversation-id cid
         :status status
         :message-count msg-count
         :message (str "Conversation " cid " has status :in-progress but contains no messages")})

      (:complete :ds-exhausted)
      (when (< msg-count 2)
        {:error :status-message-mismatch
         :conversation-id cid
         :status status
         :message-count msg-count
         :message (str "Conversation " cid " has status " status                        " but only contains " msg-count " messages")})

      ;; Unknown status
      {:error :unknown-status
       :conversation-id cid
       :status status
       :message (str "Conversation " cid " has unknown status: " status)})))

(defn validate-active-conversation-exists
  "Check that the active conversation ID references an actual conversation"
  [project]
  (when-let [active-id (:project/active-conversation project)]
    (let [conversations (:project/conversations project [])
          conversation-ids (set (map :conversation/id conversations))]
      (when-not (contains? conversation-ids active-id)
        {:error :invalid-active-conversation
         :active-id active-id
         :available-ids (vec conversation-ids)
         :message (str "Active conversation " active-id
                       " not found in conversations: " conversation-ids)}))))

;;; ============================== Inspection Functions ==============================

(defn validate-project-schema
  "Validate project against Malli schema.
   Returns {:valid? boolean :errors [...]} or {:valid? true}"
  [project]
  (if (m/validate Project project)
    {:valid? true}
    {:valid? false
     :errors (-> (m/explain Project project)
                 (me/humanize))}))

(defn find-conversation-issues
  "Find inconsistencies in conversation status vs content.
   Returns vector of issue maps, or empty vector if none"
  [project]
  (let [conversations (:project/conversations project [])]
    (->> conversations
         (map validate-conversation-status-consistency)
         (remove nil?)
         vec)))

(defn find-active-conversation-issues
  "Check if active conversation reference is valid.
   Returns issue map or nil"
  [project]
  (validate-active-conversation-exists project))

(defn inspect-project
  "Full project inspection.

   Returns:
   {:valid? boolean
    :schema-errors [...] ;; Malli schema violations
    :conversation-issues [...] ;; Status/message mismatches
    :active-conversation-issues [...] ;; Invalid active conversation
    :summary string}"
  [project-id]
  (let [project (pdb/get-project project-id)
        schema-validation (validate-project-schema project)
        conversation-issues (find-conversation-issues project)
        active-conv-issue (find-active-conversation-issues project)

        all-issues (concat
                    (when-not (:valid? schema-validation)
                      [(:errors schema-validation)])
                    conversation-issues
                    (when active-conv-issue [active-conv-issue]))

        issue-count (count all-issues)]

    {:valid? (zero? issue-count)
     :project-id project-id
     :schema-errors (when-not (:valid? schema-validation)
                      (:errors schema-validation))
     :conversation-issues conversation-issues
     :active-conversation-issues (when active-conv-issue [active-conv-issue])
     :issue-count issue-count
     :summary (if (zero? issue-count)
                "Project structure is valid"
                (str "Found " issue-count " issue(s) in project structure"))}))

;;; ============================== Utility Functions ==============================

(defn ^:diag inspect-and-report
  "Inspect project and pretty-print results.
   Usage: (inspect-and-report :sur-craft-beer)"
  [project-id]
  (let [result (inspect-project project-id)]
    (println "\n=== Project Inspection: " project-id " ===")
    (println "Valid?" (:valid? result))
    (println "Summary:" (:summary result))

    (when-let [schema-errs (:schema-errors result)]
      (println "\n--- Schema Errors ---")
      (clojure.pprint/pprint schema-errs))

    (when-let [conv-issues (:conversation-issues result)]
      (println "\n--- Conversation Issues ---")
      (doseq [issue conv-issues]
        (println "  •" (:message issue))))

    (when-let [active-issues (:active-conversation-issues result)]
      (println "\n--- Active Conversation Issues ---")
      (doseq [issue active-issues]
        (println "  •" (:message issue))))

    result))
