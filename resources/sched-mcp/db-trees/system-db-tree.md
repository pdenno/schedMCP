## System Database Tree Structure

The system database stores Discovery Schemas and metadata about all projects. It has a single root entity representing the system.

### Tree Diagram

```
System Entity (root)
├── :system/name (string, unique) - Always "SYSTEM"
├── :system/current-project (keyword) - PID of most recently worked project
├── :system/default-project-id (keyword) - Default PID for clients
│
├── :system/DS [ref, many] - Discovery Schema definitions
│   └── DS Entity
│       ├── :DS/id (keyword, unique) - Unique DS identifier (e.g., :process/warm-up-with-challenges)
│       ├── :DS/interview-objective (string) - Goal of this interview segment
│       ├── :DS/obj-str (string) - EDN-stringified complete DS definition
│       ├── :DS/budget-decrement (double) - Cost per question (default 0.05)
│       └── :DS/can-produce-visuals [keyword, many] - Visual types this DS can produce
│
├── :system/projects [ref, many] - Project metadata
│   └── Project Metadata Entity
│       ├── :project/id (keyword, unique) - Project identifier
│       ├── :project/name (string) - Project name
│       ├── :project/dir (string, unique) - Project directory path
│       ├── :project/status (keyword) - One of #{:active :archived :deleted}
│       └── :project/in-memory? (boolean) - Whether project DB is in-memory
│
├── :system/agents [ref, many]
│   └── Agent Entity
│       └── (agent attributes - OpenAI Assistant details)
│
├── :system/agent-prompts [ref, many]
│   └── Agent Prompt Entity
│       ├── :agent-prompt/id (keyword, unique) - Agent identifier
│       └── :agent-prompt/str (string) - Prompt text
│
└── :system/specs [ref, many]
    └── Spec Entity
        └── (spec attributes for completion checking)
```

### Navigation Examples

**List all Discovery Schemas:**
```clojure
[:find [?ds-id ...]
 :where
 [_ :DS/id ?ds-id]]
```

**Get DS interview objective:**
```clojure
[:find ?objective .
 :where
 [?e :DS/id :process/warm-up-with-challenges]
 [?e :DS/interview-objective ?objective]]
```

**List all active projects:**
```clojure
[:find ?pid ?name
 :where
 [?p :project/id ?pid]
 [?p :project/name ?name]
 [?p :project/status :active]]
```

**Get current project:**
```clojure
[:find ?pid .
 :where
 [_ :system/current-project ?pid]]
```

**Find system entity ID:**
```clojure
[:find ?e .
 :where
 [?e :system/name "SYSTEM"]]
```

### Key Points

- **Single root**: The system DB has exactly one system entity with `:system/name` = "SYSTEM"
- **Discovery Schemas**: All DS definitions are stored here and shared across projects
- **Project metadata**: The system DB tracks which projects exist, but actual project data is in separate project DBs
- **Cross-project queries**: Use the system DB to find patterns across multiple projects
- **Reference types**: Create parent-child relationships in the tree
- **Unique identity**: `:DS/id`, `:project/id`, `:agent-prompt/id` are unique identifiers
