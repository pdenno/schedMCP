# Database Inspection Cheat Sheet

Quick reference for database exploration after restart. There are two DBs. Namespace alias `sdb` refers to the system DB functions; `pdb` refers to project DB functions.

## Load the utilities
```clojure
(require '[sched-mcp.system-db :as sdb] :reload)
(require '[sched-mcp.projectdb :as pdb] :reload)
```
## Common inspection tasks


### See what databases exist; provides a vector of keywords naming projects
```clojure
(sdb/list-projects)
```

### Get Project content
```clojure
(pdb/get-project :sur-craft-beer) ; Get the :sur-craft-beer project.
```

### Get conversation (a projection of what you get from `pdb/get-project`).
```clojure
(pdb/get-conversation :sur-craft-beer :process) ; The process conversation
```


## Direct Datahike queries

You can use the `clojure_eval` tool anywhere to inspect DBs as you like, however, persistent source code for managing DBs should be restricted to `project_db.clj`, `system_db.clj` and `sutil.clj`.

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
