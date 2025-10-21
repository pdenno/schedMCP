(ns sched-mcp.schema
  "Database schemas for system and project databases
   Adapted from schedulingTBD with EADS -> DS renaming"
  (:require
   [sched-mcp.sutil :as sutil :refer [datahike-schema]]))

(def db-schema-sys+
  "Defines content that manages project DBs and their analysis including:
     - The project's name and db directory
     - system-level agents
     - See also db-schema-agent+ which gets merged into this."
  {
   ;; ------------------------------- Agent prompts
   :agent-prompt/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword, :unique :db.unique/identity
        :doc "a keyword naming the agent using the prompt"}
   :agent-prompt/str
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "the prompt text"}
   ;; ------------------------------- Discovery Schema
   :DS/budget-decrement
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/double
        :doc "The cost of each question, decrementing againt a budget for the entire conversation."}
   :DS/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword :unique :db.unique/identity
        :doc "A unique ID for each Discovery Schema. The namespace of the keyword is the cid, e.g. :process/flow-shop."}
   :DS/interview-objective
      #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "The objective of interviewing with this DS, (the string is also part of :DS/obj-str, but using this attribute, you can get just the objective, without all the detail."}
   :DS/obj-str
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "The stringified clojure object, it can be edn/read-string. It is the EDN version of the JSON used by the orchestrator"}
   :DS/can-produce-visuals
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/keyword
        :doc "Indicates the purpose and instructions given."}
   ;; ---------------------- project
   :project/dir ; ToDo: Fix this so that the string doesn't have the root (env var part) of the pathname.
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity
        :doc "a string naming a subdirectory containing a project."}
   :project/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword, :unique :db.unique/identity
        :doc "a keyword matching the one in the same named property of a project database"}
   :project/in-memory?
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/boolean
        :doc "a boolean indicating whether the project DB is in-memory, such as when mocking."}
   :project/name
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "a string, same as the :project/name in the project's DB."}
   :project/status
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "a keyword, current #{:active :archived :deleted}."}
;;; ---------------------- system
   :system/agent-prompts
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "Prompts used by agents"}
   :system/agents
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "an agent (OpenAI Assistant) that outputs a vector of clojure maps in response to queries."}
   :system/current-project
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword,
        :doc "the PID of the project that is now being worked, or was most recently worked."}
   :system/default-project-id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword,
        :doc "a keyword providing a project-id clients get when starting up."}
   :system/DS
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "Discovery Schema Objects"}
   :system/name
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string, :unique :db.unique/identity
        :doc "the value 'SYSTEM' to represent a single object holding data such as the current project name."}
   :system/projects
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "the projects known by the system."}
   :system/specs
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "spec objects used for checking completion of Discovery Schema, etc."}})

;;; AI Programming Assistants: The schema below has the information we aspire to collect and manage.
;;; We might not be using all of it now, but nothing here should be changed without consulting the programmer.
(def db-schema-proj+
  "Defines schema for a project plus metadata :mm/info.
   To eliminate confusion and need for back pointers, each project has its own db.
   See also db-schema-agent+ which gets merged into this."
  {;; ---------------------- Aggregate Schema-Conforming Response (ASCR)
   :ascr/budget-left
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/double
        :doc "the amount of budget left for questioning against this ASCR (against its discovery schema)."}
   :ascr/completed?
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/boolean
        :doc "true if no more interviewing required on this ASCR."}
   :ascr/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword :unique :db.unique/identity
        :doc "the discovery schema ID (DS-id) uniquely identifying the summary data structure."}
   :ascr/str
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "a string that can be edn/read-string to the data structure."}

   ;; ---------------------- box
   :box/string-val
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "boxed value"}
   :box/number-val
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/number
        :doc "boxed value"}
   :box/keyword-val
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "boxed value"}
   :box/boolean-val
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/boolean
        :doc "boxed value"}

   ;; ---------------------- claim (something believed owing to what users said in interviews)
   :claim/string
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string :unique :db.unique/identity
        :doc (str "a stringified fact (in predicate calculus) about the project, similar in concept to planning state fact in the earlier design.\n"
                  "For example, (:process/production-motivation make-to-stock).")}
   :claim/conversation-id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "The conversation from which this claim is founded. Currently a cid #{:process :data :resources :optimality}."}
   :claim/confidence
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/long
        :doc "a number [0,1] expressing how strongly we believe the proposition ."}

   ;; ---------------------- conversation
   :conversation/active-DS-id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc (str "The id of the DS instructions which is currently being pursued in this conversation.\n"
                  "If the conversation doesn't have one, functions such as ork/get-DS-id can determine it using the project's orchestrator agent."
                  "Though only one DS can truely be active at a time, we index the active-DS-id by [pid, cid] because other conversations "
                  "could still need work. See also :project/active-DS-id")}
   :conversation/status
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc (str "A keyword that specifies the status of the conversation. Thus far, the only values are :ds-exhausted :in-progress, and "
                  ":not-started. :in-progress only means that this conversation can be pursued further. For this conversation to be the one "
                  "currently being pursued, it must also be the case that :project/active-conversation is this conversation.")}
   :conversation/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword :unique :db.unique/identity
        :doc "a keyword uniquely identifying the kind of conversation; so far just #{:process :data :resources :optimality}."}
   :conversation/messages
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref
        :doc "the messages between the interviewer and interviewees of the conversation."}

   ;; ---------------------- message
   :message/answers-question
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/long
        :doc "an optional property that refers to a :message/id of a question for which this response is deemed to be a answer."}
   :message/code
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "Code produced at this point in the conversation."}
   :message/code-execution
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "Result of running code produced at this point in the conversation."}
   :message/content
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "a string with optional html links."}
   :message/from
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "The agent issuing the message, #{:human :surrogate :system}."}
   :message/graph--ffbd
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "An optional graph that is the response, or part of the response of a user."}
   :message/graph--orm
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "An optional graph that is the response, or part of the response of a user."}
   :message/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/long
        :doc (str "The unique ID of a message. These are natural numbers starting at 0, but owing to 'LLM:' prompts,\n"
                  "which aren't stored, some values can be skipped. Because these are not unique to the DB, they are not :db.unique/identity.")}
   :message/pursuing-DS
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "The DS-id of the discovery schema for which this question or answer is based."}
   :message/SCR
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "a string that can be edn/read-string into a Schema-Conforming Response (SCR)."}
   :message/tags
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/keyword
        :doc "Optional keywords used to classify the message."}
   :message/table
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "An optional table that is the response, or part of the response of a user, or is produced by an interviewer.."}
   :message/time
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/instant
        :doc "The time at which the message was sent."}

   ;; ---------------------- project -- the top-level object; DB is a tree, not graph.
   :project/active-conversation
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc (str "The conversation most recently busy, #{:process...}. Note that several conversations can still need work, and there can "
                  "be an :conversation/active-DS-id on several, however, this is the conversation to start if coming back to the project.")}
   :project/active-DS-id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc "The most recently purused discovery schema in the project. See also :conversation/active-DS-id."}
   :project/agents
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref,
        :doc "an agent (OpenAI Assistant, etc.) that outputs a vector of clojure maps in response to queries."}
   :project/claims
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref
        :doc "the collection of things we believe about the project as logical statements."}
   :project/conversations
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref
        :doc "The conversations of this project."}
   :project/desc ; ToDo: If we keep this at all, it would be an annotation on an ordinary :message/content.
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "the original paragraph written by the user describing what she/he wants done."}
   :project/execution-status ; ToDo: If we keep this at all, it would be an annotation on an ordinary :message/content.
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword
        :doc ":running means resume-conversation can work (not that it necessarily is)
              :paused means resume-conversation will not work."}
   :project/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword :unique :db.unique/identity
        :doc "a lowercase kebab-case keyword naming a project; unique to the project."}
   :project/in-memory?
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/boolean
        :doc "a boolean indicating whether the project DB is in-memory, such as when mocking."}
   :project/name
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "4 words or so describing the project; e.g. 'craft brewing production scheduling'"}
   :project/ork-aid
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "The orchestrator agent id (OpenAI notion)."}
   :project/ork-tid
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc (str "The thread-id of orchestrator agent.\n"
                  "When this does not match the current ork-agent, "
                  "the agent needs a more expansive CONVERSATION-HISTORY message for the conversation.")}
   :project/ASCRs
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref
        :doc "Aggregated Schema-Conforming Responses indexed by their DS-id, :ascr/id."}
   :project/surrogate
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/ref
        :doc "the project's surrogate object, if any."}
   :project/surrogate? ; ToDo: Not used?
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/boolean
        :doc "true if domain expertise is provided by an artificial agent."}
   :project/tables
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/ref
        :doc "true if domain expertise is provided by an artificial agent."}})

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
