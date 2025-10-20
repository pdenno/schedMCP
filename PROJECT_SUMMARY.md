# Sched MCP Project Summary
## Project Overview

SchedMCP is a Model Context Protocol (MCP) server for creating manufacturing scheduling systems using MiniZinc and combinatorial optimization solvers.
It is research software about human/AI teaming on long-running and complex endeavors (where production scheduling is that endeavor).
When completed (it is in development right now) it will mentor users in the use of the MiniZinc DSL, and help users validate their solution.

The project allows AI assistants to:
- Interview users about their scheduling challenge. ('users' are often AI agents acting as human surrogates)
- Create and refine a MiniZinc solution (future)
- Integrate with their production system to run the scheduling solution they have created. (future)

## Key File Paths and Descriptions

### Core Source Directories
- `src/sched_mcp/` - Main source code
  - `interviewing/` - LangGraph-based autonomous interview subsystem
  - `tools/` - MCP tool implementations (iviewr, orch, surrogate)
- `test/` - Test code
- `resources/` - System resources
  - `sched-mcp/prompts/` - LLM prompts
  - `agents/` - Agent instruction files

### Documentation
- `docs/` - Technical documentation
  - `LangGraph-interviewer.md` - Autonomous interview architecture
  - `current-state.md` - Current implementation status
  - `development-plan.md` - Migration plan from schedulingTBD

### Resources
- `resources/agents/base-iviewr-instructions.md` - Base interviewer agent instructions
- `resources/sched-mcp/resources/ORCHESTRATOR_GUIDE.md` - Orchestrator guidance
- `resources/mcp-orchestrator-guide.md` - MCP orchestrator guide

## MCP Components

### SchedMCP MCP Tools

There are three major roles involved with an interview: the orchestrator, the interviewer, and the interviewees (typically AI surrogates of human experts).
Note that the prefix of tools used in these roles are, respectively `orch_`, `iviewr_` and `sur_`

#### Interview Tools

**Active Tools:**
- `iviewr_conduct_interview` - **PRIMARY TOOL** - Autonomous LangGraph-based interviewer that conducts entire Discovery Schema interviews. Takes a DS ID and budget, returns completed ASCR after autonomous Q&A loop.
- `sys_get_current_ds` - Check current Discovery Schema and aggregated knowledge (ASCR)

**Deprecated/Legacy Tools:**
- `iviewr_formulate_question` - **(DEPRECATED)** Use a discovery schema to formulate a question. Replaced by autonomous LangGraph interviewing in `iviewr_conduct_interview`.
- `iviewr_interpret_response` - **(DEPRECATED)** Interpret responses into Schema-Conforming Response (SCR). Replaced by autonomous LangGraph interviewing in `iviewr_conduct_interview`.

#### Orchestration Tools

**Active Tools:**
- `orch_get_next_ds` - Choose a Discovery Schema (discussion topic) for delegation of interviewing to the interviewer.
- `orch_get_progress` - Get overall interview progress.

**Deprecated/Legacy Tools:**
- `orch_start_ds_pursuit` - **(DEPRECATED)** Begin working on a specific Discovery Schema. LangGraph handles DS pursuit internally via `iviewr_conduct_interview`.
- `orch_complete_ds` - **(DEPRECATED)** Mark a Discovery Schema as complete. LangGraph handles completion detection internally.

#### Surrogate Expert Tools (for testing)
- `sur_start_expert` - Initialize a domain expert simulation for testing
- `sur_answer` - Get expert responses to questions (used by LangGraph interview nodes)

### LangGraph Interviewing Subsystem

The interviewing subsystem (`src/sched_mcp/interviewing/`) implements autonomous Discovery Schema interviews using LangGraph4j. This replaces the previous MCP-tool-based Q&A cycle management.

**Architecture:**
- **Autonomous Interview Loop**: Single `iviewr_conduct_interview` tool call conducts entire DS interview
- **Graph Nodes**: formulate-question → check-budget → get-answer → interpret-response → evaluate-completion
- **State Management**: `InterviewState` record with channel-based reducers for ASCR accumulation
- **Completion Criteria**: DS-specific logic via multimethods in `ds_util.clj`

**Key Files:**
- `lg_util.clj` - LangGraph4j integration utilities (channels, state conversion, node/edge wrappers)
- `interview_state.clj` - `InterviewState` record and state management helpers
- `interview_nodes.clj` - Node implementations (formulate, interpret, evaluate, etc.)
- `interview_graph.clj` - Graph construction and execution (`run-interview`, `build-interview-graph`)
- `ds_util.clj` - Discovery Schema utilities and completion criteria
- `ds_graph.clj` - DS flow graph for orchestration
- `domains.clj` - Domain multimethod dispatching

**Discovery Schema Implementations** (`interviewing/domain/`):
- **Process Domain** (`process/`):
  - `warm_up_with_challenges.clj` - Initial exploration and pain points
  - `flow_shop.clj` - Sequential production flow
  - `job_shop.clj` - Flexible routing workflows (3 variants: basic, constrained, unconstrained)
  - `scheduling_problem_type.clj` - Problem classification
  - `timetabling.clj` - Time-based scheduling
- **Data Domain** (`data/`):
  - `orm.clj` - Object-Role Modeling for domain relationships

**State Flow:**
1. Initial state: Empty ASCR, budget (e.g., 10.0), conversation messages
2. Each Q&A cycle: Question formulated → Answer obtained → SCR extracted → ASCR updated
3. Completion: When required ASCR fields present or budget exhausted
4. Return: Completed ASCR stored in project DB

### Clojure-MCP MCP Tools

These tools are available during development but can be disabled in production:

- `LS` - Returns a recursive tree view of files and directories starting from the specified path.
- `read_file` - Smart file reader with pattern-based exploration for Clojure files.
- `grep` - Fast content search tool that works with any codebase size. Finds the paths to files that have matching contents using regular expressions.
- `glob_files` - Fast file pattern matching tool that works with any codebase size. Supports glob patterns like "**/*.clj" or "src/**/*.cljs".
- `think` - Use the tool to think about something. It will not obtain new information or make any changes to the repository, but just log the thought.
- `clojure_inspect_project` - Analyzes and provides detailed information about a Clojure project's structure, including dependencies, source files, namespaces, and environment details.
- `clojure_eval` - Takes a Clojure Expression and evaluates it in the current namespace. For example, providing "(+ 1 2)" will evaluate to 3.
- `bash` - Execute bash shell commands on the host system.
- `clojure_edit` - Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.
- `clojure_edit_replace_sexp` - Replaces Clojure expressions in a file.
- `file_edit` - Edit a file by replacing a specific text string with a new one.
- `file_write` -  Write a file to the local filesystem. Overwrites the existing file if there is one.
- `dispatch_agent` - Launch a new agent that has access to read-only tools.
- `architect` - Analyzes requirements and breaks them down into clear, actionable implementation steps.
- `code_critique` - Starts an interactive code review conversation that provides constructive feedback on your Clojure code.
- `clojure_edit_agent` - Specialized Clojure code editor that efficiently applies multiple code changes using structural editing tools.
- `scratch_pad` - A persistent scratch pad for storing structured data between tool calls.

### Resource Directories

- `/resources/sched-mcp/prompts/` - System prompts for AI assistants
- `/resources/sched-mcp/prompts/system/` - Core system prompts (ds-interpret.txt, etc.)
- `/resources/agents/` - Agent-specific instruction files

### Documentation (that are also MCP resources)
- `./PROJECT_SUMMARY.md` - This document
- `./README.md` - Overview
- `/docs/development-plan.md` - The development plan for migrating from an older code called schedulingTBD to schedMCP
- `/docs/LangGraph-interviewer.md` - Detailed documentation of the LangGraph-based autonomous interviewing architecture

## Current Architecture

```
src/sched_mcp/
├── mcp_core.clj              # MCP server setup & component registration
├── main.clj                  # Entry point
├── llm.clj                   # LLM integration (OpenAI, Claude)
├── tool_system.clj           # Multimethod-based tool framework
├── project_db.clj            # Project database management (Datahike)
├── system_db.clj             # System database (Discovery Schemas)
├── sutil.clj                 # Shared utilities, DB connection helpers
├── schema.clj                # Database schema definitions
├── nrepl.clj                 # nREPL server for development
├── util.clj                  # General utilities
├── file_content.clj          # File content utilities
├── prompts.clj               # Prompt management
├── resources.clj             # MCP resource management
├── interviewing/             # LangGraph-based autonomous interviewing subsystem
│   ├── lg_util.clj           # LangGraph4j utilities (channels, state conversion)
│   ├── interview_state.clj   # InterviewState record & helpers
│   ├── interview_nodes.clj   # Graph node implementations
│   ├── interview_graph.clj   # Graph construction & execution
│   ├── ds_util.clj           # Discovery Schema utilities
│   ├── ds_graph.clj          # DS flow graph for orchestration
│   ├── domains.clj           # Domain multimethod dispatching
│   └── domain/
│       ├── process/          # Process-type Discovery Schemas
│       │   ├── warm_up_with_challenges.clj
│       │   ├── flow_shop.clj
│       │   ├── job_shop.clj
│       │   ├── job_shop_c.clj
│       │   ├── job_shop_u.clj
│       │   ├── scheduling_problem_type.clj
│       │   └── timetabling.clj
│       └── data/             # Data-type Discovery Schemas
│           └── orm.clj
└── tools/                    # MCP tool implementations
    ├── iviewr/
    │   └── core.clj          # Interviewer MCP tools (formulate, interpret, conduct-interview)
    ├── iviewr_tools.clj      # Legacy interview management tools
    ├── orch/
    │   └── core.clj          # Orchestrator MCP tools (get-next-ds, get-progress)
    └── surrogate/
        ├── core.clj          # Surrogate expert MCP tools
        └── sur_util.clj      # Surrogate utilities
```

## Dependencies and Versions

### Mount 

- `mount` is a Clojure library for managing app state (starting and stopping components, etc.) based on namespace dependencies.

```clojure
(defstate system-and-project-dbs
  :start (init-all-dbs!)
  :stop (alog! "Shutting down database connections..."))
```

### Core Dependencies

- `org.clojure/clojure` (1.12.1) - The Clojure language
- `io.modelcontextprotocol.sdk/mcp` (0.10.0) - Model Context Protocol SDK
- `nrepl/nrepl` (1.3.1) - Network REPL server for Clojure
- `io.github.bsorrentino/langgraph4j` - LangGraph4j for autonomous interview state management

### Architecture Patterns

1. **Factory Function Pattern**: The refactored architecture uses factory functions:
   - `make-tools!`: Returns seq of tool registration maps
   - `make-prompts!`: Returns seq of prompt registration maps
   - `make-resources!`: Returns seq of resource registration maps
   - All components created through `mcp-core/build-and-start-mcp-server`

2. **Multimethod Dispatch**: The tool system uses multimethods for extensibility:
   - `tool-name`: Determines the name of a tool
   - `tool-description`: Provides human-readable description
   - `tool-schema`: Defines the input/output schema
   - `validate-inputs`: Validates tool inputs
   - `execute-tool`: Performs the actual operation
   - `format-results`: Formats the results for the AI

3. **LangGraph State Management**: The interviewing subsystem uses LangGraph4j patterns:
   - **Channel Reducers**: `make-reducer-channel` for ASCR accumulation
   - **Appender Channels**: `make-appender-channel` for message history
   - **State Conversion**: Bidirectional conversion between Clojure maps and Java AgentState
   - **Conditional Edges**: Loop until completion criteria met or budget exhausted

4. **Core/Tool Separation**: Each tool follows a pattern:
   - `core.clj`: Pure functionality without MCP dependencies
   - MCP tools use multimethod dispatch for integration

5. **Persistent State Management**: Through Datahike project and system databases

## Development Workflow Recommendations

1. **Setup and Configuration**:
   - Configure Claude Desktop with the Sched MCP server
   - Set up file system and Git integration if needed
   - Use `clojure -M:dev` and `(start)` from user namespace for local development

2. **Testing Interviews**:
   - Use `sur_start_expert` to create surrogate experts for testing
   - Call `iviewr_conduct_interview` with DS ID to run autonomous interview
   - Check ASCR accumulation and completion criteria

3. **Development**:
   - nREPL-connect to running system (port specified in startup)
   - Use `(develop.repl/ns-setup!)` to set up namespace aliases
   - Check system state via `@mcore/components-atm`

## Recent Organizational Changes

### Migration to LangGraph-Based Autonomous Interviewing

**Completed (Phases 1-4):**
- ✅ LangGraph4j infrastructure with channel reducers and state management
- ✅ `InterviewState` record for idiomatic Clojure state handling
- ✅ Autonomous interview loop with conditional flow (formulate → answer → interpret → evaluate)
- ✅ Real LLM integration for question formulation and response interpretation
- ✅ MCP tool integration via `iviewr_conduct_interview`
- ✅ Surrogate expert integration for testing
- ✅ ASCR and message storage in project DB

**Architecture Shift:**
- **Before**: Orchestrator managed every Q&A cycle using `iviewr_formulate_question` and `iviewr_interpret_response`
- **After**: Orchestrator delegates entire DS interview to `iviewr_conduct_interview`, which runs autonomously

**Legacy Tools Retained:**
- `iviewr_formulate_question` and `iviewr_interpret_response` marked as deprecated but retained for backwards compatibility
- `orch_start_ds_pursuit` and `orch_complete_ds` marked as deprecated (LangGraph handles internally)

**Benefits:**
- Reduced token usage (single tool call vs. multiple round-trips per Q&A)
- Sophisticated interview strategies possible within LangGraph
- Cleaner separation of strategic (orchestrator) and tactical (interviewer) concerns
- Easier testing and development of interview logic

### Mount-Based Component Lifecycle

**Use of Mount for starting everything**: The system now uses Mount for managing component lifecycle. 
Previously, we started the MCP loop separately. Now all components (databases, nREPL, MCP server) start via Mount's `(start)` function.

---

*Last Updated: October 2024*
