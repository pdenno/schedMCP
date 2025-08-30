(ns sched-mcp.schema
  "Database schemas for system and project databases
   Adapted from schedulingTBD with EADS -> DS renaming"
  (:require
   [sched-mcp.sutil :as sutil :refer [datahike-schema]]))

(def db-schema-sys+
  "System database schema - manages projects"
  {;; Project registry
   :project/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Project identifier"}

   :project/name
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Human-readable project name"}

   :project/created-at
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/instant
        :doc "When project was created"}

   :project/domain
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Manufacturing domain (e.g., food-processing, metalworking)"}

   :project/status
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Project status: :active, :archived, :deleted"}})

(def db-schema-proj+
  "Project database schema - manages conversations and interview state
   Adapted from schedulingTBD schema with EADS renamed to DS"
  {;; ---------------------- project
   :project/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Project identifier matching system DB"}

   :project/name
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Human-readable project name"}

   :project/conversations
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/ref
        :doc "The conversations of this project"}

   :project/active-conversation
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "The conversation most recently active"}

   :project/active-ds-id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "The most recently pursued DS in the project"}

   :project/summary-dstructs
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/ref
        :doc "Summary data structures indexed by their DS-id"}

   :project/surrogate
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "The project's surrogate object, if any"}

   :project/surrogate?
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/boolean
        :doc "True if domain expertise is provided by a surrogate"}

   ;; ---------------------- conversation
   :conversation/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Unique conversation identifier"}

   :conversation/started-at
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/instant
        :doc "When conversation started"}

   :conversation/status
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Conversation status: :active, :paused, :complete, :ds-exhausted"}

   :conversation/active-ds-id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "The id of the DS currently being pursued in this conversation"}

   :conversation/active-pursuit
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "Reference to the active pursuit"}

   :conversation/messages
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/ref
        :doc "The messages between interviewer and interviewees"}

   :conversation/expert-type
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Expert type: :human or :surrogate"}

   :conversation/surrogate-config
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "EDN string of surrogate configuration"}

   ;; ---------------------- message (from schedulingTBD)
   :message/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Unique message identifier"}

   :message/conversation
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "Reference to parent conversation"}

   :message/timestamp
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/instant
        :doc "When message was created"}

   :message/from
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "The agent issuing the message: :user, :human, :surrogate, or :system"}

   :message/content
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Message content"}

   :message/type
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Message type: :question, :answer, :system"}

   :message/pursuing-ds
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "The DS being pursued by this question or answer"}

   :message/ds-data-structure
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "EDN string of DS data structure inferred from conversation"}

   :message/question-type
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "A label from interview instructions for this Q&A"}

   :message/answers-question
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "Reference to the message this answers"}

   :message/table
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Optional table in response"}

   :message/scr
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Schema-Conforming Response as EDN string"}

   :message/pursuit
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "Reference to the pursuit this message belongs to"}

   ;; ---------------------- summary data structures (EADS -> DS)
   :dstruct/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "The DS-id uniquely identifying the summary data structure"}

   :dstruct/str
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "EDN string of the data structure"}

   :dstruct/budget-left
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/double
        :doc "The amount of budget left for questioning"}})

(def db-schema-sys
  "Complete system database schema as Datahike schema list"
  (datahike-schema db-schema-sys+))

(def db-schema-proj
  "Complete project database schema as Datahike schema list"
  (datahike-schema db-schema-proj+))

(defn project-schema-key?
  "Check if a keyword is part of the project schema"
  [k]
  (contains? db-schema-proj+ k))

(defn system-schema-key?
  "Check if a keyword is part of the system schema"
  [k]
  (contains? db-schema-sys+ k))