# MCP Server Debugging Guide

## Common Issues and Solutions

### 1. Server Won't Stay Connected
**Symptom**: Server disconnects immediately after initialization

**Solution**: Ensure `run-server` stays in main thread
```clojure
;; In main.clj
(defn -main [& _args]
  (mount/start)
  (mcp-core/run-server mcp-core/server-config))  ; Blocks main thread
```

### 2. JSON-RPC Parse Errors
**Symptom**: "Unexpected token" errors in MCP log

**Causes & Solutions**:
- **Logging to stdout**: All logging must go to files or stderr, never stdout
- **Debug output**: Comment out println statements
- **Telemere console handler**: Remove it with `(tel/remove-handler! :default/console)`

### 3. Protocol Version Mismatch
**Symptom**: Server disconnects after initialization

**Solution**: Match Claude's expected version
```clojure
(defn handle-initialize [id params server-info]
  (success-response id
    {:protocolVersion "2025-06-18"  ; Must match client version
     :capabilities {:tools {}}
     :serverInfo server-info}))
```

### 4. Hot Reload Not Working
**Issue**: Code changes don't take effect without restart

**Current Limitation**: MCP server captures tool functions at startup

**Workaround from REPL**:
```clojure
;; Reload namespace
(require '[sched-mcp.tools.iviewr-tools] :reload)

;; Update server config
(alter-var-root #'mcp/server-config
  (fn [old] (assoc old :tool-specs tools/tool-specs)))
```

**Note**: This only works for new connections, not existing server loop

### 5. Logging Not Appearing
**Issue**: alog! messages not in agents-log.edn

**Check**:
1. Handler is configured: `(tel/get-handlers)`
2. File handler exists: Look for `:agent/log`
3. Force flush: `(tel/stop-handlers!)` then restart

### 6. Database Connection Errors
**Common causes**:
- Database not registered in `databases-atm`
- Database file doesn't exist
- Schema mismatch

**Debug**:
```clojure
(require '[sched-mcp.db :as db])
(db/list-all-dbs)  ; Check registration
(db/db-info :your-db-id)  ; Check connection
```

## Testing MCP Tools

From REPL:
```clojure
;; Direct tool testing
(require '[sched-mcp.tools.iviewr-tools :as tools])
(tools/start-interview-tool {:project_name "Test" :domain "test"})

;; Check logs
(slurp "logs/agents-log.edn")
```

## File Locations

- MCP server log: `~/.config/Claude/logs/mcp-server-schedmcp.log`
- Agent log: `./logs/agents-log.edn`
- Error output: `./logs/mcp-stderr.log`
- Wrapper script: `./bin/mcp-clean.sh`
