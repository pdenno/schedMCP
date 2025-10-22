## Project Database Tree Structure

The project database is organized as a tree with a single root entity representing the project.

### Tree Diagram

```
Project Entity (root)
├── :project/id (keyword) - Unique project identifier
├── :project/name (string) - Human-readable project name
├── :project/active-conversation (keyword) - Currently active conversation
├── :project/active-DS-id (keyword) - Most recently pursued Discovery Schema
├── :project/execution-status (keyword) - :running or :paused
├── :project/in-memory? (boolean) - Whether DB is in-memory
├── :project/desc (string) - Original user description
├── :project/ork-aid (string) - Orchestrator agent ID
├── :project/ork-tid (string) - Orchestrator thread ID
│
├── :project/conversations [ref, many]
│   └── Conversation Entity
│       ├── :conversation/id (keyword) - One of #{:process :data :resources :optimality}
│       ├── :conversation/status (keyword) - One of #{:not-started :in-progress :ds-exhausted}
│       ├── :conversation/active-DS-id (keyword) - Currently active DS for this conversation
│       └── :conversation/messages [ref, many]
│           └── Message Entity
│               ├── :message/id (long) - Sequential message number
│               ├── :message/from (keyword) - One of #{:system :human :surrogate :developer-injected}
│               ├── :message/content (string) - Message text
│               ├── :message/timestamp (instant) - When message was sent
│               ├── :message/pursuing-DS (keyword) - DS being pursued
│               ├── :message/answers-question (long) - Message ID of question being answered
│               ├── :message/SCR (string) - Schema-Conforming Response (EDN string)
│               ├── :message/tags [keyword, many] - Classification tags
│               ├── :message/table (string) - Optional table data
│               ├── :message/graph--orm (string) - Optional ORM diagram
│               ├── :message/graph--ffbd (string) - Optional FFBD diagram
│               ├── :message/code (string) - Optional code
│               └── :message/code-execution (string) - Optional code execution result
│
├── :project/ASCRs [ref, many]
│   └── ASCR Entity (Aggregated Schema-Conforming Response)
│       ├── :ascr/id (keyword, unique) - Discovery Schema ID this ASCR corresponds to
│       ├── :ascr/str (string) - EDN-stringified ASCR data structure
│       ├── :ascr/completed? (boolean) - Whether DS is marked complete
│       └── :ascr/budget-left (double) - Remaining question budget
│
├── :project/claims [ref, many]
│   └── Claim Entity (Logical propositions about the project)
│       ├── :claim/string (string, unique) - Stringified predicate calculus fact
│       ├── :claim/conversation-id (keyword) - Source conversation
│       └── :claim/confidence (long) - Confidence level [0,1]
│
├── :project/surrogate [ref, one]
│   └── Surrogate Entity
│       └── :surrogate/system-instruction (string) - System instruction for surrogate expert
│
├── :project/agents [ref, many]
│   └── Agent Entity
│       └── (agent attributes - OpenAI Assistant details)
│
└── :project/tables [ref, many]
    └── Table Entity
        └── (table attributes)
```

### Navigation Examples

**Get all ASCRs:**
```clojure
[:find ?ds-id ?completed
 :where
 [?p :project/ASCRs ?ascr]
 [?ascr :ascr/id ?ds-id]
 [?ascr :ascr/completed? ?completed]]
```

**Get messages in process conversation:**
```clojure
[:find [?msg-id ...]
 :where
 [?c :conversation/id :process]
 [?c :conversation/messages ?m]
 [?m :message/id ?msg-id]]
```

**Find project entity ID:**
```clojure
[:find ?e .
 :where
 [?e :project/id]]
```

### Key Points

- **Single root**: Every project DB has exactly one project entity
- **Reference types** (`:db.type/ref`): Create parent-child relationships in the tree
- **Cardinality many**: Creates collections (e.g., multiple messages, multiple ASCRs)
- **Unique identity**: `:project/id`, `:conversation/id`, `:ascr/id`, `:claim/string` are unique identifiers
- **No cycles**: The structure is a true tree, not a graph with cycles
