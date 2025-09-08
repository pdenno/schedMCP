# schedMCP - Manufacturing Scheduling Interview System

schedMCP is an MCP (Model Context Protocol) server that conducts structured interviews to understand manufacturing scheduling challenges and help build MiniZinc scheduling specifications through human/AI collaboration.

## Overview

This project reimplements the core interview functionality from schedulingTBD as an MCP server, allowing AI assistants like Claude to conduct scheduling interviews through a structured tool interface.

## Architecture

The system uses an orchestrator-driven approach that dynamically selects appropriate Discovery Schemas based on the current state of knowledge. Key components:

### Interview Tools
- `iviewr_formulate_question` - Generate contextual questions from Discovery Schemas and current knowledge
- `iviewr_interpret_response` - Extract structured data (SCRs) from natural language answers

### System Query Tools
- `sys_get_current_ds` - Check current Discovery Schema and aggregated knowledge (ASCR)
- `sys_get_interview_progress` - View overall interview progress

### Orchestration Tools
- `orch_get_next_ds` - Get comprehensive DS status for orchestration decisions
- `orch_start_ds_pursuit` - Begin working on a specific Discovery Schema
- `orch_complete_ds` - Mark a Discovery Schema as complete
- `orch_get_progress` - Get overall interview progress

### Surrogate Expert Tools (for testing)
- `sur_start_expert` - Initialize a domain expert simulation
- `sur_answer` - Get expert responses to questions
- `sur_get_session` - Debug surrogate session state

## Prerequisites

1. **Clojure** (1.11+)
2. **Environment Variables**:
   ```bash
   export SCHED_MCP_DB=./test/dbs      # Where the system and project DBs are stored
   export OPENAI_API_KEY=sk-...        # If using OpenAI for future features
   export NIST_RCHAT=sk-...            # If using NIST RChat
   ```

## Installation

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd schedMCP
   ```

2. Create database directories:
   ```bash
   mkdir -p $SCHEDULING_TBD_DB/projects
   mkdir -p $SCHEDULING_TBD_DB/system
   ```

## Running the Server

### Standalone Mode
```bash
clojure -M -m sched-mcp.main
```

### Development Mode (with REPL)
```clojure
;; Start a REPL
clojure -M:dev

;; In the REPL
(user/start)  ; This will start mount components and the server
```

## Configuring Claude Desktop

Add to your Claude Desktop configuration file:
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "schedmcp": {
      "command": "clojure",
      "args": ["-M", "-m", "sched-mcp.main"],
      "cwd": "/path/to/schedMCP",
      "env": {
        "SCHED_MCP_DB": <path to directory, possibly in the project ./test/dbs/"
      }
    }
  }
}
```

## Usage Example

Once configured, you can interact with the scheduling interview system in Claude Desktop:

### Starting an Interview

**You**: "I need help with scheduling for my craft beer brewery"

**Claude** will use the orchestration tools to:
1. Check available Discovery Schemas
2. Select the most appropriate starting point based on current knowledge
3. Begin asking contextual questions

### Interview Flow

The orchestrator dynamically manages the interview by:
- **Selecting Discovery Schemas** - Choosing the next area to explore based on what's already known
- **Generating Questions** - Using LLMs to create natural, contextual questions
- **Interpreting Responses** - Extracting structured data (SCRs) from conversational answers
- **Building Knowledge** - Aggregating responses into a comprehensive understanding (ASCR)
- **Determining Completion** - Knowing when enough information has been gathered

### Discovery Schema Types

The system includes various Discovery Schemas for different aspects:
- **Process Schemas** - Understanding workflow types (flow-shop, job-shop, timetabling)
- **Data Schemas** - Capturing domain relationships (Object-Role Modeling)
- **Challenge Schemas** - Identifying scheduling pain points
- **Resource Schemas** - Mapping available resources and constraints

## Project Structure

```
schedMCP/
├── src/sched_mcp/
│   ├── core.clj          # Mount state management
│   ├── main.clj          # MCP server entry point
│   ├── mcp_core.clj      # Core MCP protocol implementation
│   ├── sutil.clj         # Shared utilities
│   ├── interview.clj     # Interview management
│   ├── orchestration.clj # Dynamic DS selection and flow
│   ├── ds_loader.clj     # Discovery Schema loading
│   ├── ds_combine.clj    # SCR to ASCR aggregation
│   ├── ds_schema.clj     # Database schema for DS tracking
│   ├── surrogate.clj     # Domain expert simulation
│   └── tools/
│       ├── registry.clj  # Central tool registry
│       ├── iviewr/       # Interviewer tools
│       ├── orch/         # Orchestration tools
│       └── surrogate.clj # Surrogate expert tools
├── deps.edn              # Dependencies
└── README.md            # This file
```

## Development

### Adding New Discovery Schemas

1. Add the DS JSON file to `resources/discovery-schemas/`
2. Implement combination logic in `ds_combine.clj`
3. Add completion criteria in the appropriate namespace
4. The orchestrator will automatically include it in the flow

### Testing Tools

You can test individual tools in the REPL:

```clojure
;; Test with surrogate expert
(def project (:project_id (sur-start-expert {:domain "craft-beer"})))

;; Check available Discovery Schemas
(orch-get-next-ds {:project_id project :conversation_id "conv-1"})

;; Start a specific DS
(orch-start-ds-pursuit {:project_id project
                        :conversation_id "conv-1"
                        :ds_id "process/warm-up-with-challenges"})

;; Generate and answer questions
(def q (iviewr-formulate-question {:project_id project
                                   :conversation_id "conv-1"
                                   :ds_id "process/warm-up-with-challenges"}))
(def a (sur-answer {:project_id project :question (:question q)}))
(iviewr-interpret-response {:project_id project
                            :conversation_id "conv-1"
                            :ds_id "process/warm-up-with-challenges"
                            :answer (:answer a)
                            :question_asked (:question q)})
```

## Troubleshooting

### Database Connection Issues
- Ensure `SCHEDULING_TBD_DB` is set and the directory exists
- Check write permissions on the database directory

### MCP Connection Issues
- Check Claude Desktop logs for connection errors
- Ensure the server is running and accessible
- Verify the configuration path is absolute, not relative

### Interview State Issues
- Each project maintains its own database
- Use `get_interview_context` to check current state
- Projects persist between server restarts

## Future Enhancements

- [ ] Additional Discovery Schema templates
- [ ] MiniZinc model generation from collected knowledge
- [ ] Advanced table-based communication for complex data entry
- [ ] Integration with schedulingTBD's visualization tools
- [ ] Support for resuming interviews across sessions
- [ ] Multi-user/multi-project management
- [ ] Export interview results to various formats
- [ ] LangGraph integration for more sophisticated orchestration

## Contributing

This project is part of NIST's Human/AI Teaming for Manufacturing Digital Twins research. Contributions and feedback are welcome!

## License

[License information to be added]
