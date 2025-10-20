# schedMCP - Manufacturing Scheduling Interview System

schedMCP is an MCP (Model Context Protocol) server that conducts structured interviews to understand manufacturing scheduling challenges and helps build MiniZinc scheduling solutions.

It is both a research prototype studying human/AI teaming and a practical tool for small and medium-sized manufacturers to build production scheduling systems.

## Overview

This project implements autonomous interview functionality using LangGraph and the Model Context Protocol, allowing AI assistants like Claude to conduct sophisticated scheduling interviews through a structured tool interface.

The implementation borrows ideas and code from [clojure-mcp](https://github.com/bhauman/clojure-mcp). A snapshot can be found at `examples/clojure-mcp`, and the predecessor project schedulingTBD is at `examples/schedulingTBD`.

## Key Features

- **Autonomous Interviewing** - LangGraph-based system conducts multi-turn Discovery Schema interviews autonomously
- **Dynamic Orchestration** - Orchestrator selects appropriate Discovery Schemas based on current knowledge state
- **Surrogate Experts** - AI domain experts simulate manufacturing experts for testing and development
- **Persistent State** - Datahike databases maintain interview state across sessions
- **Extensible Schema System** - 8 Discovery Schemas for different aspects of scheduling (process, data, resources, optimality)

## Architecture

### Three-Tier Interview System

**Orchestrator** (Strategic Level)
- Selects which Discovery Schema to pursue next
- Analyzes knowledge gaps in collected ASCRs
- Determines when sufficient information is gathered
- Tools: `orch_get_next_ds`, `orch_get_progress`

**Interviewer** (Tactical Level - LangGraph Autonomous)
- Conducts entire Discovery Schema interviews autonomously via `iviewr_conduct_interview`
- Formulates contextual questions using LLMs and DS templates
- Obtains answers from surrogates or humans
- Interprets responses into structured SCRs
- Accumulates SCRs into ASCR via state reduction
- Evaluates completion criteria and budget
- Returns completed ASCR to orchestrator

**Interviewees** (Domain Experts)
- Surrogate experts simulate manufacturing domain knowledge
- Human experts (future) provide real-world scheduling insights
- Tools: `sur_start_expert`, `sur_answer`

### LangGraph Interview Loop

The autonomous interviewer uses a LangGraph state machine:

```
START → formulate-question → check-budget → get-answer 
      → interpret-response → evaluate-completion
      → [if complete] END
      → [if not complete] loop back to formulate-question
```

**State Management:**
- `InterviewState` record tracks: DS ID, ASCR, messages, budget, completion flag
- Channel reducers accumulate SCRs into ASCR
- Appender channels maintain conversation history
- Budget tracking prevents runaway loops

### Databases

- **System DB** (`src/sched_mcp/system_db.clj`) - Discovery Schemas, project registry, shared data
- **Project DBs** (`src/sched_mcp/project_db.clj`) - Per-project interview state, ASCRs, messages, pursuits
- **Schema** (`src/sched_mcp/schema.clj`) - Datahike graph DB schema for both databases

### Discovery Schema System

8 implemented Discovery Schemas in `src/sched_mcp/interviewing/domain/`:

**Process Domain:**
- `warm-up-with-challenges` - Initial exploration and pain points
- `flow-shop` - Sequential production processes
- `job-shop` (3 variants) - Flexible routing workflows
- `scheduling-problem-type` - Problem classification
- `timetabling` - Time-based scheduling

**Data Domain:**
- `orm` - Object-Role Modeling for domain relationships

Each DS includes:
- Annotated JSON schema showing desired information structure
- Interview objective describing what to learn
- Completion criteria (multimethod in `ds_util.clj`)
- Budget allocation

## Prerequisites

1. **Clojure** (1.12+)
2. **Java** (11+)
3. **Environment Variables**:
   ```bash
   export SCHED_MCP_DB=./test/dbs      # Where system and project DBs are stored
   export OPENAI_API_KEY=sk-...        # For OpenAI LLM calls
   export ANTHROPIC_API_KEY=sk-...     # For Claude LLM calls (alternative)
   ```

## Installation

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd schedMCP
   ```

2. Create database directories:
   ```bash
   mkdir -p $SCHED_MCP_DB/projects
   mkdir -p $SCHED_MCP_DB/system
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
(start)  ; Mount starts all components: databases, nREPL, MCP server

;; Set up namespace aliases
(develop.repl/ns-setup!)

;; Check components
@mcore/components-atm  ; {:tools [...], :prompts [...], :resources [...]}
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
      "command": "<PATH TO schedMCP>/bin/mcp-wrapper.sh",
      "env": {
        "SCHED_MCP_DB": "./test/dbs/",
        "OPENAI_API_KEY": "sk-...",
        "MCP_MODE": "true"
      }
    }
  }
}
```

## Usage Example

Once configured, interact with the scheduling interview system in Claude Desktop:

### Starting an Interview with a Surrogate Expert

**You**: "Let's start an interview with an AI surrogate expert for craft beer production scheduling"

**Claude** will:
1. Create a surrogate expert using `sur_start_expert` with domain "craft-beer"
2. Use `orch_get_next_ds` to determine which Discovery Schema to pursue first
3. Call `iviewr_conduct_interview` which autonomously:
   - Formulates contextual questions based on the DS template
   - Gets answers from the surrogate expert
   - Interprets answers into structured SCRs
   - Accumulates SCRs into the ASCR
   - Continues until completion criteria met or budget exhausted
4. Returns the completed ASCR to the orchestrator
5. Repeats with next DS until full understanding is achieved

### Interview Flow - Autonomous LangGraph Execution

The key innovation is **autonomous interviewing** via LangGraph:

**Old Approach (Deprecated):**
- Orchestrator managed every Q&A cycle
- Required `iviewr_formulate_question` → `sur_answer` → `iviewr_interpret_response` for each question
- High token usage, tight coupling

**Current Approach (LangGraph):**
- Single `iviewr_conduct_interview` tool call
- Autonomous multi-turn Q&A loop
- Returns completed ASCR
- Orchestrator only makes strategic decisions

**Example Autonomous Interview:**
```clojure
;; Orchestrator calls this ONCE:
(iviewr-conduct-interview 
  {:project_id :craft-beer-123
   :conversation_id :process
   :ds_id :process/warm-up-with-challenges
   :budget 10.0})

;; Returns after autonomous interview:
{:status "success"
 :ds_id "warm-up-with-challenges"
 :ascr {:product-or-service-name "craft beers"
        :scheduling-challenges ["bottleneck-processes" "equipment-utilization"]
        :one-more-thing "Tank availability constraints..."}
 :complete true
 :message_count 6
 :budget_remaining 4.0
 :summary "Interview completed with 6 messages. DS criteria met."}
```

### Tool Prefixes by Persona

- **`orch_`** - Orchestrator tools (strategic decisions)
- **`iviewr_`** - Interviewer tools (autonomous Q&A execution)
- **`sur_`** - Surrogate expert tools (simulated domain experts)
- **`sys_`** - System utilities (general purpose)

## Project Structure

```
schedMCP/
├── src/sched_mcp/
│   ├── mcp_core.clj           # MCP server & component registration
│   ├── main.clj               # Entry point
│   ├── schema.clj             # Datahike schema definitions
│   ├── project_db.clj         # Project database functions
│   ├── system_db.clj          # System database functions
│   ├── sutil.clj              # Shared utilities
│   ├── llm.clj                # LLM integration (OpenAI, Claude)
│   ├── tool_system.clj        # Multimethod tool framework
│   ├── interviewing/          # LangGraph autonomous interviewing
│   │   ├── lg_util.clj        # LangGraph4j utilities
│   │   ├── interview_state.clj # State record & helpers
│   │   ├── interview_nodes.clj # Graph node implementations
│   │   ├── interview_graph.clj # Graph construction & execution
│   │   ├── ds_util.clj        # DS utilities & completion criteria
│   │   ├── ds_graph.clj       # DS flow graph
│   │   ├── domains.clj        # Domain dispatching
│   │   └── domain/            # Discovery Schema implementations
│   │       ├── process/       # Process-type DSs (7 schemas)
│   │       └── data/          # Data-type DSs (1 schema)
│   └── tools/                 # MCP tool implementations
│       ├── iviewr/
│       │   └── core.clj       # Interviewer tools
│       ├── orch/
│       │   └── core.clj       # Orchestrator tools
│       └── surrogate/
│           ├── core.clj       # Surrogate expert tools
│           └── sur_util.clj   # Surrogate utilities
├── test/                      # Unit tests
├── resources/
│   ├── sched-mcp/prompts/     # LLM prompts
│   └── agents/                # Agent instructions
├── docs/                      # Technical documentation
├── deps.edn                   # Dependencies
└── README.md                  # This file
```

## Development

### Testing with Autonomous Interviews

Test the complete autonomous interview flow in the REPL:

```clojure
;; 1. Start surrogate expert
(require '[sched-mcp.tools.surrogate.core :as sur])
(def result (tool-system/execute-tool 
              (sur/create-start-surrogate-tool) 
              {:domain "craft-beer"}))
(def pid (:project_id result))

;; 2. Check which DS to pursue
(require '[sched-mcp.tools.orch.core :as orch])
(tool-system/execute-tool 
  (orch/create-get-next-ds-tool)
  {:project_id pid :conversation_id :process})

;; 3. Run autonomous interview (LangGraph)
(require '[sched-mcp.tools.iviewr.core :as iviewr])
(def interview-result
  (tool-system/execute-tool
    (iviewr/create-conduct-interview-tool)
    {:project_id pid
     :conversation_id :process
     :ds_id :process/warm-up-with-challenges
     :budget 10.0}))

;; Result includes completed ASCR, message count, budget remaining
(:ascr interview-result)
(:message_count interview-result)
(:complete interview-result)

;; 4. Check progress
(tool-system/execute-tool
  (orch/create-get-progress-tool)
  {:project_id pid})
```

### Testing Individual Components (Advanced)

For development of interview nodes:

```clojure
;; Test LangGraph interview loop directly
(require '[sched-mcp.interviewing.interview-graph :as igraph])
(require '[sched-mcp.interviewing.interview-state :as istate])

(def initial-state
  (istate/map->InterviewState
    {:ds-id :process/warm-up-with-challenges
     :ascr {}
     :messages []
     :budget-left 10.0
     :complete? false
     :pid pid
     :cid :process}))

(def final-state (igraph/run-interview initial-state))

;; Check final ASCR
(:ascr final-state)
```

### Adding New Discovery Schemas

1. Create new namespace in `src/sched_mcp/interviewing/domain/`
2. Implement DS using `defmethod` for:
   - `ds-id` - Unique identifier
   - `DS` - Annotated JSON schema
   - `interview-objective` - What to learn
   - `budget-decrement` - Cost per question
   - `completion-criteria` - When DS is done (via `ds-complete?`)
3. Register in `domains.clj`
4. Add to DS flow graph in `ds_graph.clj`

Example:
```clojure
(ns sched-mcp.interviewing.domain.process.my-new-ds
  (:require [sched-mcp.interviewing.ds-util :as dsu]))

(defmethod dsu/ds-id :process/my-new-ds [_] :process/my-new-ds)

(defmethod dsu/DS :process/my-new-ds [_]
  {:my-field "example value"
   :my-list ["item1" "item2"]})

(defmethod dsu/interview-objective :process/my-new-ds [_]
  "Learn about XYZ aspect of the scheduling problem")

(defmethod dsu/budget-decrement :process/my-new-ds [_] 1.0)

(defmethod dsu/ds-complete? :process/my-new-ds [_ ascr]
  (and (contains? ascr :my-field)
       (seq (:my-list ascr))))
```

## Troubleshooting

### Database Connection Issues
- Ensure `SCHED_MCP_DB` is set and directory exists
- Check write permissions on database directory
- Verify `$SCHED_MCP_DB/projects/` and `$SCHED_MCP_DB/system/` exist

### MCP Connection Issues
- Check Claude Desktop logs for connection errors
- Ensure server is running and accessible
- Verify configuration path is absolute, not relative
- Check environment variables are set in config

### Interview State Issues
- Each project has its own database in `$SCHED_MCP_DB/projects/`
- Use `sys_get_current_ds` to check current ASCR state
- Use `orch_get_progress` to check overall progress
- Projects persist between server restarts

### LangGraph Interview Issues
- Check LLM API keys are valid (`OPENAI_API_KEY` or `ANTHROPIC_API_KEY`)
- Verify Discovery Schema is loaded in system DB
- Check logs for node execution errors
- Verify surrogate expert is initialized before interview

## Current Status

### ✅ Implemented (Production Ready)
- LangGraph-based autonomous interviewing (Phases 1-4 complete)
- 8 Discovery Schemas (7 process, 1 data)
- Surrogate expert system with 6+ domains
- Orchestrator with dynamic DS selection
- Datahike persistence (system + project DBs)
- Mount-based component lifecycle
- nREPL development server

### 🚧 In Development
- Additional Discovery Schemas (resources, optimality)
- Human expert interview UI
- MiniZinc model generation from ASCRs
- Advanced table-based data entry

### 📋 Future Enhancements
- Multi-user/multi-project management
- Interview session resume/replay
- Export interview results to various formats
- Integration with schedulingTBD visualization tools
- Advanced orchestration strategies
- Interview quality metrics and validation

## Documentation

- **PROJECT_SUMMARY.md** - Comprehensive technical overview
- **docs/LangGraph-interviewer.md** - Autonomous interview architecture details
- **docs/current-state.md** - Implementation status
- **docs/development-plan.md** - Migration roadmap from schedulingTBD
- **docs/database-management.md** - Database schema and operations

## Contributing

This project is part of NIST's Human/AI Teaming for Manufacturing Digital Twins research. Contributions and feedback are welcome!

## License

[License information to be added]

---

*For detailed technical documentation, see PROJECT_SUMMARY.md*
