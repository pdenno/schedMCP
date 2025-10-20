# Database Management and Testing Infrastructure

## Overview

This document describes the database management and testing infrastructure implemented for schedMCP, following patterns from schedulingTBD but adapted for the MCP architecture.

## Architecture

### Database Hierarchy

```
$SCHEDULING_TBD_DB/
├── system/              # System database - tracks all projects and global config
└── projects/            # Project databases - one per scheduling project
    ├── brewery-1/
    ├── glass-factory/
    ├── pencil-factory/
    └── ...
```

### Key Components

1. **System Database** (`system_db.clj`)
   - Central registry of all projects
   - Manages project lifecycle (create, archive, delete)
   - Automatically initialized on system startup

2. **Project Database Management** (`project_db.clj`)
   - Mount defstate for automatic initialization
   - Database inspection and debugging tools
   - Testing helper functions

3. **Mount Integration**
   - Mount documentation: [Managing Clojure and ClojureScript app state with Mount](https://github.com/tolitius/mount).
   - `system-and-project-dbs` defstate (found in `src/project_db.clj`) automatically:
     - Initializes system database if needed
     - Registers all existing project databases
     - Handles proper startup/shutdown

## Implementation Details

### System Database Schema

```clojure
;; Project management
:project/id          ; Unique keyword identifier
:project/name        ; Human-readable name
:project/domain      ; Manufacturing domain
:project/created-at  ; Timestamp
:project/status      ; :active, :archived, :deleted

;; System configuration
:system/name         ; Always "SYSTEM"
:system/projects     ; References to all projects
```

### Project Database Schema

```clojure
;; Project identification
:project/id          ; Matches system DB
:project/name        ; Human-readable name

;; Conversation management
:conversation/id     ; Unique identifier
:conversation/status ; :active, :paused, :complete
:conversation/current-ds    ; Current Discovery Schema
:conversation/completed-ds  ; List of completed DS
:conversation/messages      ; References to messages

;; Discovery Schema tracking
:ds/id               ; Schema identifier
:ds/ascr             ; Aggregated Schema-Conforming Response
:ds/status           ; :active, :complete, :abandoned
:ds/questions-asked  ; Question count
:ds/budget-remaining ; Questions left in budget
```

On `mount/start`:
1. System database initialized (created if needed)
2. All project databases discovered and registered
3. Connections established and cached

## API Reference

### System Database Functions

```clojure
;; Project lifecycle
(sdb/create-project! {:project-id "id"
                         :project-name "Name"
                         :domain "domain"})
(sdb/list-projects)
(sdb/get-project "project-id")
(sdb/archive-project! "project-id")
(sdb/delete-project! "project-id")

;; System management
(sdb/ensure-system-db!)
(sdb/init-system-db!)
```

### Database Management Functions

```clojure
;; Inspection
(db/list-all-dbs)
(db/db-info :system)
(db/list-projects)
(db/get-project-info "project-id")
(db/find-conversations "project-id")
(db/db-stats)

;; Testing helpers
(db/create-test-project! :name "Test" :domain "manufacturing")
(db/reset-system-db!)  ; WARNING: Deletes all data
(db/test-interview-flow)

;; Conversation management
(db/update-conversation-state! project-id conversation-id updates)
(db/add-conversation-message! project-id conversation-id message)
(db/get-conversation-state project-id conversation-id)
```

### User Namespace Conveniences

```clojure
(start)      ; Start system with mount
(stop)       ; Stop system
(restart)    ; Restart system
(status)     ; Show system status
(projects)   ; List all projects
(project id) ; Get project details
(create-project "Name" :domain "domain")
(test-flow)  ; Run test scenario
```

## Testing Workflows

### Starting Fresh

```clojure
(require '[user])
(in-ns 'user)
(start)
(db/reset-system-db!)  ; Optional: clear everything
(test/create-sample-projects!)
```

### Testing Interview Flow

```clojure
(def proj-id (db/create-test-project!))
(test/test-discovery-schema-flow proj-id (str proj-id "-conv-1"))
```

### Debugging Databases

```clojure
(db/list-all-dbs)                    ; All registered DBs
(db/db-info :system)                 ; Detailed DB info
(db/recent-transactions :system 5)   ; Recent changes
(keys @sutil/databases-atm)          ; Raw registry
```

## Configuration

### Environment Variables

- `SCHEDULING_TBD_DB`: Base directory for databases (required)
  - Example: `/opt/scheduling`
  - Test mode: `./test/dbs` (auto-detected in REPL)

### Directory Structure

Created automatically:
```bash
$SCHEDULING_TBD_DB/
├── system/
│   └── [datahike database files]
└── projects/
    ├── project-id-1/
    │   └── [datahike database files]
    └── project-id-2/
        └── [datahike database files]
```

## Design Decisions

1. **Mount for Lifecycle Management**
   - Ensures databases initialized before other components
   - Clean startup/shutdown semantics
   - Automatic project discovery

2. **System Database as Registry**
   - Central source of truth for all projects
   - Enables project discovery on restart
   - Soft deletes for safety

3. **Separate Project Databases**
   - Isolation between projects
   - Easy backup/restore per project
   - Scalable to many projects

4. **Test Helpers Integration**
   - Quick project creation with timestamps
   - Full system reset capability
   - Convenient REPL workflows

## Migration from Manual Management

Previous approach required manual database creation and registration. New approach:

1. System database created automatically
2. Projects tracked centrally
3. Automatic discovery on startup
4. No manual registration needed

## Future Enhancements

1. **Backup/Restore**
   - Project-level backup functions
   - System database snapshots
   - Point-in-time recovery

2. **Multi-tenancy**
   - User/organization support
   - Access control
   - Project sharing

3. **Database Migrations**
   - Schema versioning
   - Automatic migrations
   - Backward compatibility

## Troubleshooting

### "Database not found"
- Check `SCHEDULING_TBD_DB` environment variable
- Verify directory permissions
- Run `(sdb/ensure-system-db!)`

### "Project not loading"
- Check `(db/list-all-dbs)`
- Verify project in system DB: `(sdb/list-projects)`
- Manual registration: `(db/register-project-dbs!)`

### "Connection errors"
- Check mount state: `(mount/running-states)`
- Restart: `(user/restart)`
- Check logs for initialization errors

## Summary

This infrastructure provides:
- Automatic database management
- Clean system lifecycle with Mount
- Comprehensive testing tools
- Easy REPL-driven development
- Foundation for complex scheduling interviews

The system now handles all database management automatically, allowing developers to focus on building scheduling capabilities rather than infrastructure concerns.
