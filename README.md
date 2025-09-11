# schedMCP - Manufacturing Scheduling Interview System

schedMCP is an MCP (Model Context Protocol) server that conducts structured interviews to understand manufacturing scheduling challenges and help build MiniZinc scheduling specifications through human/AI collaboration.

## Overview

This project reimplements the core interview functionality from schedulingTBD as an MCP server, allowing AI assistants like Claude to conduct scheduling interviews through a structured tool interface.
Its implementation-- currently under development --borrows ideas and code from [clojure-mcp](https://github.com/bhauman/clojure-mcp)
For use with AI programming assistants, schedulingTBD can be found in `examples/schedulingTBD` and a snapshot of clojure-mcp can be found at `examples/clojure-mcp`.



## Architecture

### Databases
- `src/sched-mcp/schema.clj` - Datahike (graph DB) schema for the system and project DBs
- `src/sched-mcp/project-db.clj` - functions to create and manage project databases. A project database is created for each of what interviewees view as a human/AI teaming project to build a scheduling system.
- `src/sched-mcp/system-db.clj` - functions to maintain knowledge of projects (active, soft deleted, or archived), and other shared data objects such as discovery schema.

The system uses an orchestrator-driven approach that dynamically selects appropriate Discovery Schemas based on the current state of knowledge. Key MCP tools:

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

### Starting an Interview with a Surrogate Expert

**You**: "Let's start an interview with an AI surrogate expert for scheduling production of craft beer"

**Claude** will use the orchestration tools to:
1. Check available Discovery Schemas
2. Select the most appropriate starting point based on current knowledge
3. Begin asking contextual questions
4. Complete

### Interview Flow

The system is predominantly focused on developing the Discovery Schema methodology of interviewing using AI surrogate experts.
(There are provisions for interviewing actual humans, of course, but these currently aren't used as much.)
Interviewing is performed by Claude running MCP.
To make the interviewing process easily comprehensible, the MCP tool names used in interview have prefixes indicating the 'persona' to which they associate.
For example, `iviewr_formulate_question` is a tool used by the interviewer persona, whereas `sur_answer` is the response to the formulated question provided by the domain surrogate (e.g. the craft beer expert).
There are three persona: orchestrator (prefix `orch_`), interviewer (prefix `iviewr_`) and surrogate (prefix `sur_`).
Tools that are generally useful, not just to those personas, are prefixed with `sys_`.

#### Orchestrator
The orchestrator dynamically manages the interview by:
- **Selecting Discovery Schemas** - Choosing the next area to explore based on what's already known,
- **Building Knowledge** - Aggregating responses, known as Schema-Conforming Responses (SCRs) into a comprehensive understanding called an Aggregated Schema-Conforming Response (ASCR),
- **Determining Completion** - Knowing when enough information has been gathered.

#### Interviewer
The interview participates by:
- **Generating Questions** - Using LLMs to create natural, contextual questions
- **Interpreting Responses** - Extracting structured data, each a Schema-Conforming Response (SCRs), from conversational answers from the surrogate or human.

#### Intervieweees
The surrogate or humans participate by:
- **Answering Questions** - questions posed by the interviewer. Questions and responses can sometimes be posed using tables, rather than just unformated text.
The surrogate experts should aim to provide a coherent description of the company's operations and production challenges it faces, just as you would expect from humans.

### Discovery Schema Types

The system includes various Discovery Schemas for different aspects:
- **Process Schemas** - Understanding workflow types (flow-shop, job-shop, timetabling)
- **Data Schemas** - Capturing domain relationships (Object-Role Modeling)
- **Resource Schemas** - Mapping available resources and constraints (currently not developed)
- **Optimality Schemas** - Defining the properties (such as KPI) sought in good schedules, implemented through the solution's MiniZinc objective function.

## Project Structure

```
schedMCP/
├── src/sched_mcp/
│   ├── schema.clj        # Schema for the project and system DBs
│   ├── project_db.clj    # Functions to manage the project DBs.
│   ├── mcp_core.clj      # MCP server entry point
│   ├── sutil.clj         # Shared utilities
│   ├── interview.clj     # Interview management
│   ├── surrogate.clj     # Domain expert simulation
│   └── tools/
│       ├── registry.clj  # Central tool registry
│       ├── iviewr/       # Interviewer tools
│       ├── iviewr/domain # Discovery schema for process, data, resources, and optimality.
│       ├── orch/         # Orchestration tools
│       └── surrogate.clj # Surrogate expert tools
├── deps.edn              # Dependencies (libraries used)
└── README.md             # This file
├── test/                 # Unit tests, each named by the `src` it supports.
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
