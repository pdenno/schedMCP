(ns sched-mcp.schema
  "Database schemas for system and project databases"
  (:require
   [sched-mcp.sutil :as sutil :refer [datahike-schema]]))

(def db-schema-sys+
  "System database schema - manages projects and system-level information"
  {;; Project management
   :project/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Unique keyword identifier for a project"}

   :project/name
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Human-readable project name"}

   :project/domain
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Manufacturing domain (e.g., 'food-processing', 'metalworking')"}

   :project/created-at
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/instant
        :doc "Timestamp when project was created"}

   :project/status
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Project status: :active, :archived, :deleted"}

   :project/conversation-id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Primary conversation ID for the project"}

   ;; System configuration
   :system/name
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :unique :db.unique/identity
        :doc "System identifier - always 'SYSTEM'"}

   :system/projects
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/ref
        :doc "References to all known projects"}

   :system/default-project
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Default project ID for new users"}})

(def db-schema-proj+
  "Project database schema - manages conversations and interview state"
  {;; Project identification
   :project/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Project identifier matching system DB"}

   :project/name
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Human-readable project name"}

   ;; Conversation management
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
        :doc "Conversation status: :active, :paused, :complete"}

   :conversation/current-ds
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Current Discovery Schema being pursued"}

   :conversation/completed-ds
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/keyword
        :doc "List of completed Discovery Schemas"}

   :conversation/messages
   #:db{:cardinality :db.cardinality/many
        :valueType :db.type/ref
        :doc "References to conversation messages"}

   ;; Surrogate support
   :conversation/expert-type
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Expert type: :human or :surrogate"}

   :conversation/surrogate-config
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "EDN string of surrogate configuration (domain, company-name, etc.)"}

   ;; Message tracking
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
        :doc "The agent issuing the message: :human, :surrogate, or :system"}

   :message/type
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Message type: :question, :answer, :system"}

   :message/content
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Message content"}

   :message/ds-id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "Associated Discovery Schema ID"}

   ;; Discovery Schema tracking
   :ds/id
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :unique :db.unique/identity
        :doc "Discovery Schema identifier"}

   :ds/ascr
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/string
        :doc "Aggregated Schema-Conforming Response (as EDN string)"}

   :ds/status
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/keyword
        :doc "DS status: :active, :complete, :abandoned"}

   :ds/questions-asked
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/long
        :doc "Number of questions asked for this DS"}

   :ds/budget-remaining
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/long
        :doc "Questions remaining in budget"}

   ;; Project-level surrogate support (following schedulingTBD pattern)
   :project/surrogate
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/ref
        :doc "Reference to the project's conversation with surrogate"}

   :project/surrogate?
   #:db{:cardinality :db.cardinality/one
        :valueType :db.type/boolean
        :doc "True if domain expertise is provided by a surrogate"}})

;; Convert to Datahike format
(def db-schema-sys (datahike-schema db-schema-sys+))
(def db-schema-proj (datahike-schema db-schema-proj+))

(defn project-schema-key?
  "Check if a key belongs to the project schema"
  [k]
  (contains? db-schema-proj+ k))

(defn system-schema-key?
  "Check if a key belongs to the system schema"
  [k]
  (contains? db-schema-sys+ k))