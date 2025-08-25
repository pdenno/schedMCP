# Database Inspection Cheat Sheet

Quick reference for database exploration after restart.

## Load the utilities
```clojure
(require '[sched-mcp.db :as db] :reload)
```

## Common inspection tasks

### See what databases exist
```clojure
(db/list-all-dbs)
```

### Check a specific database
```clojure
(db/db-info :post-restart-brewery)
(db/db-info :system)
```

### List all projects
```clojure
(db/list-projects)
```

### Find conversations in a project
```clojure
(db/find-conversations :post-restart-brewery)
```

### Inspect an entity
```clojure
;; First find entity IDs
(db/list-entities :post-restart-brewery)

;; Then inspect specific entity
(db/inspect-entity :post-restart-brewery entity-id)
```

### Recent activity
```clojure
(db/recent-transactions :post-restart-brewery 10)
```

### Quick stats
```clojure
(db/db-stats)
```

## Direct Datahike queries

```clojure
(require '[datahike.api :as d])
(require '[sched-mcp.sutil :as sutil])

;; Get connection
(def conn (sutil/connect-atm :post-restart-brewery))

;; Query example - find all conversations
(d/q '[:find ?cid ?status
       :where 
       [?c :conversation/id ?cid]
       [?c :conversation/status ?status]]
     @conn)

;; Pull example - get full conversation
(d/pull @conn '[*] [:conversation/id :conv-1756144856221])
```

## Check interview answers

```clojure
;; Get EADS data (interview warm-up data)
(require '[sched-mcp.warm-up :as warm-up])
(warm-up/get-eads-data :post-restart-brewery :conv-1756144856221)
```

## Architecture Notes

### Current Setup
- **Datahike**: Persistent storage for projects, conversations, answers
- **Mount**: Component lifecycle management  
- **MCP**: Communication protocol with Claude Desktop

### Proposed Enhancement
- **LangGraph**: Ephemeral conversation state and flow control
- **Benefits**: 
  - Cleaner separation of concerns
  - Better conversation flow management
  - Easier to implement complex interview logic

### Database Structure
- System DB (`:system`): Projects registry, EADS definitions
- Project DBs (`:project-id`): Conversations, messages, interview data
