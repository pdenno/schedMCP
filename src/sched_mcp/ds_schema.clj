(ns sched-mcp.ds-schema
  "Database schema extensions for Discovery Schema management"
  (:require
   [datahike.api :as d]))

;;; Additional schema for DS state management

(def ds-schema
  "Schema attributes for Discovery Schema tracking"
  [;; DS pursuit tracking
   {:db/ident :pursuit/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for a DS pursuit"}

   {:db/ident :pursuit/ds-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "The Discovery Schema being pursued"}

   {:db/ident :pursuit/conversation
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the conversation"}

   {:db/ident :pursuit/started
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this DS pursuit started"}

   {:db/ident :pursuit/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Status: :active, :complete, :abandoned"}

   {:db/ident :pursuit/budget-allocated
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Question budget allocated"}

   {:db/ident :pursuit/budget-used
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Question budget used so far"}

   ;; SCR storage
   {:db/ident :message/scr
    :db/valueType :db.type/string ; EDN string
    :db/cardinality :db.cardinality/one
    :db/doc "Schema-Conforming Response extracted from this message"}

   {:db/ident :message/pursuit
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Which DS pursuit this message belongs to"}

   ;; ASCR storage
   {:db/ident :ascr/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for an ASCR"}

   {:db/ident :ascr/ds-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Which Discovery Schema this ASCR is for"}

   {:db/ident :ascr/conversation
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to conversation"}

   {:db/ident :ascr/data
    :db/valueType :db.type/string ; EDN string
    :db/cardinality :db.cardinality/one
    :db/doc "The aggregated schema-conforming response data"}

   {:db/ident :ascr/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Version number for optimistic locking"}

   {:db/ident :ascr/updated
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Last update timestamp"}

   ;; Extended conversation attrs
   {:db/ident :conversation/active-pursuit
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Currently active DS pursuit"}

   {:db/ident :conversation/completed-ds
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc "Set of completed DS IDs"}])

(defn add-ds-schema!
  "Add DS schema to existing database"
  [conn]
  (d/transact conn ds-schema))
