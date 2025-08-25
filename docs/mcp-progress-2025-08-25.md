# schedMCP Development Progress

## Date: 2025-08-25

### What We Accomplished

1. **MCP Server Setup**
   - Successfully configured schedMCP as an MCP server for Claude Desktop
   - Fixed JSON-RPC protocol issues (stdout pollution from logging)
   - Updated protocol version to match Claude's expectation ("2025-06-18")
   - Added support for `notifications/initialized` message

2. **Logging Configuration**
   - Set up agent logging to `logs/agents-log.edn`
   - Added `alog!` calls to MCP tools for debugging
   - Configured Telemere to avoid stdout output (MCP requirement)

3. **Database Utilities**
   - Created `src/sched_mcp/db.clj` with inspection and management functions
   - Key functions:
     - `list-all-dbs` - Show all registered databases
     - `db-info` - Detailed database information
     - `list-projects` - List projects from system DB
     - `find-conversations` - Find conversations in project DB
     - Functions for LangGraph integration (conversation state management)

### Current State

- MCP server is running and connected
- Interview system is working (created "Post-Restart Brewery" project)
- Logging is operational and writing to `logs/agents-log.edn`
- Database inspection tools are available in REPL

### Key Files Modified

1. `src/sched_mcp/mcp_core.clj` - Added notification handling, fixed protocol version
2. `src/sched_mcp/util.cljc` - Configured logging for MCP compatibility
3. `src/sched_mcp/tools/iviewr_tools.clj` - Added alog! debugging
4. `src/sched_mcp/main.clj` - Fixed server loop to prevent early exit
5. `bin/mcp-clean.sh` - Created clean wrapper for MCP server
6. `src/sched_mcp/db.clj` - NEW: Database utilities

### Configuration

Claude Desktop config (`~/.config/Claude/claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "schedmcp": {
      "command": "/home/pdenno/Documents/git/schedMCP/bin/mcp-clean.sh"
    }
  }
}
```

### Next Steps

1. Integrate LangGraph for ephemeral conversation state
2. Fix the `get_interview_answers` null pointer error
3. Enhance database inspection tools
4. Add hot-reload capability to MCP server
5. **Implement Discovery Schema (EADS)** - The core idea from schedulingTBD
   - Discovery schema defines expert-defined ways of collecting schema-conforming responses (SCR)
   - See docs/SERC-AI4SE-2025-denno-v9.pdf for theoretical background
   - Reference implementation: examples/schedulingTBD/src/server/scheduling_tbd/iviewr/domain/process/warm-up-with-challenges.clj
