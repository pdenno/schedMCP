# schedMCP - Manufacturing Scheduling Interview System

schedMCP is an MCP (Model Context Protocol) server that conducts structured interviews to understand manufacturing scheduling challenges and help build MiniZinc scheduling specifications through human/AI collaboration.

## Overview

This project reimplements the core interview functionality from schedulingTBD as an MCP server, allowing AI assistants like Claude to conduct scheduling interviews through a structured tool interface.

## Architecture

The system exposes scheduling interview capabilities through MCP tools:
- `start_interview` - Initialize a new scheduling project and begin the interview
- `get_interview_context` - Check current interview state and next questions
- `submit_answer` - Submit answers to interview questions
- `get_interview_answers` - Review collected answers

## Prerequisites

1. **Clojure** (1.11+)
2. **Environment Variables**:
   ```bash
   export SCHEDULING_TBD_DB=/opt/scheduling  # Directory for project databases
   export OPENAI_API_KEY=sk-...              # If using OpenAI for future features
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
(require '[sched-mcp.core :as core])
(core/start)  ; This will start mount components and the server
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
        "SCHEDULING_TBD_DB": "/opt/scheduling"
      }
    }
  }
}
```

## Usage Example

Once configured, you can interact with the scheduling interview system in Claude Desktop:

### Starting an Interview

**You**: "I need help with scheduling for my craft beer brewery"

**Claude** will use the `start_interview` tool to create a project and begin asking questions:
- What are the main scheduling challenges you face?
- What products does your brewery produce?
- Any other constraints or goals?

### Continuing the Interview

The system maintains conversation state, so you can answer questions naturally. Claude will use the tools to:
- Track your answers
- Determine the next relevant questions
- Build up a complete picture of your scheduling needs

### Interview Phases

1. **Warm-up Phase** (currently implemented):
   - General scheduling challenges
   - Product/service description
   - Additional context

2. **Future Phases** (to be implemented):
   - Resource identification
   - Process flow mapping
   - Constraint specification
   - MiniZinc model generation

## Project Structure

```
schedMCP/
├── src/sched_mcp/
│   ├── core.clj          # Mount state management
│   ├── main.clj          # MCP server entry point
│   ├── mcp_core.clj      # Core MCP protocol implementation
│   ├── sutil.clj         # Shared utilities
│   ├── interview.clj     # Interview management
│   ├── warm_up.clj       # Warm-up phase implementation
│   └── tools/
│       └── interview.clj # MCP tool definitions
├── deps.edn              # Dependencies
└── README.md            # This file
```

## Development

### Adding New Interview Phases

1. Create a new namespace for the phase (e.g., `sched-mcp.resource-mapping`)
2. Define the question structure and logic
3. Add tools to `sched-mcp.tools.interview`
4. Update the interview flow in `sched-mcp.interview`

### Testing Tools

You can test individual tools in the REPL:

```clojure
(require '[sched-mcp.tools.interview :as tools])

;; Test starting an interview
((:tool-fn tools/start-interview-tool-spec)
 {:project_name "Test Brewery" :domain "food-processing"})

;; Test getting context
((:tool-fn tools/get-interview-context-tool-spec)
 {:project_id "test-brewery"})
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

- [ ] Additional interview phases (resources, processes, constraints)
- [ ] MiniZinc model generation
- [ ] Integration with schedulingTBD's visualization tools
- [ ] Support for resuming interviews across sessions
- [ ] Multi-user/multi-project management
- [ ] Export interview results to various formats

## Contributing

This project is part of NIST's Human/AI Teaming for Manufacturing Digital Twins research. Contributions and feedback are welcome!

## License

[License information to be added]
