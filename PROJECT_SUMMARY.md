# Sched MCP Project Summary
## Project Overview

SchedMCP is a Model Context Protocol (MCP) server for creating manufacturing scheduling systems using MiniZinc and combinatorial optimization solvers.
It is research software about human/AI teaming on long-running and complex endeavors (where production scheduling is that endeavor).
When completed (it is in development right now) it will mentor users in the use of the MiniZinc DSL, and help users validate their solution

The project allows AI assistants to:
- Interview users about their scheduling challenge. ('users' are often AI agents acting as human surrogates)
- Create and refine a MiniZinc solution (future)
- Integrate with their production system to run the scheduling solution they have created. (future)

Importantly, the code integrates parts of Clojure-mcp, a MCP-based coding assistant for Clojure. In the development of SchedMCP, Clojure-mcp MCP tools, prompts, and resources (components) can be used in MCP loop.
In what will become typical 'production' execution of SchedMCP clojure-mcp components will be made unavailable. See `src/mcp_core.clj` to understand how this is achieved.

## Key File Paths and Descriptions

- `src/` - source
- `test/` - test
- `examples/schedulingTBD`: The base directory of the project schedulingTBD, which inspired this MCP-based version.
- `examples/clojure-mcp`: A well-designed MCP server implementing an AI programming assistant for Clojure; we hope to steal from it!


- `examples/clojure-mcp/` - MCP patterns we borrow (but don't depend on in the sense of deps.edn)
- `resources/agents/base-interviewer-instructions.md` -

## MCP Components
### SchedMCP MCP Tools

There are three major roles involved with an interview: the orchestrator, the interviewer, and the interviewees (typically AI surrogates of human experts).
Note that the prefix of tool used in these roles are, respectively `orch_`, `iviewr_` and `sur_`
The Orchestrator manages of interview; it dynamically choose the next topic to be discussed by the interviewer and interviewees. Selecting a Discovery Schema is how the topic is indicated to the interviewer.


#### Interview Tools
- `iviewr_formulate_question` - Use a discovery schema to formulate a question to pose to a human or AI scheduling expert.
- `iviewr_interpret_response` - Interpret the response from the exprt into a Schema-Conforming Response (SCR).

#### Orchestration Tools
- `orch_get_next_ds` - Choose a Discovery Schema (discussion topic) for delegation of interviewing to the interviewer.
- `orch_start_ds_pursuit` - Begin working on a specific Discovery Schema.
- `orch_complete_ds` - Mark a Discovery Schema as complete; this is indicated by the completeness of the Aggregated Schema-Conforming Response (ASCR).
- `orch_get_progress` - Get overall interview progress.

#### Surrogate Expert Tools (for testing)
- `sur_start_expert` - Initialize a domain expert simulation
- `sur_answer`- Get expert responses to questions

#### General Use Query Tools
- `sys_get_current_ds` - Check current Discovery Schema and aggregated knowledge (ASCR)
- `sys_get_interview_progress` - View overall interview progress

### Clojure-MCP MCP Tools

- `LS` - Returns a recursive tree view of files and directories starting from the specified path.
         The path parameter must be an absolute path, not a relative path. You should generally prefer the `glob_files` tool, if you know which directories to search.
- `read_file` - Smart file reader with pattern-based exploration for Clojure files.
- `grep` - Fast content search tool that works with any codebase size.\n- Finds the paths to files that have matching contents using regular expressions.\n- Supports full regex syntax (eg. \"log.*Error\", \"function\\s+\\w+\", etc.).
- `glob_files` - Fast file pattern matching tool that works with any codebase size. - Supports glob patterns like \"**/*.clj\" or \"src/**/*.cljs\".\n - Returns matching file paths sorted by modification time (most recent first).
- `think` - Use the tool to think about something. It will not obtain new information or make any changes to the repository, but just log the thought. Use it when complex reasoning or brainstorming is needed.
- `clojure_inspect_project` - Analyzes and provides detailed information about a Clojure project's structure, including dependencies, source files, namespaces, and environment details.
- `clojure_eval` - Takes a Clojure Expression and evaluates it in the current namespace. For example, providing \"(+ 1 2)\" will evaluate to 3.
- `bash` - Execute bash shell commands on the host system.
- `clojure_edit` - Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.
- `clojure_edit_replace_sexp` - Replaces Clojure expressions in a file.
- `file_edit` - Edit a file by replacing a specific text string with a new one. For safety, this tool requires that the string to replace appears exactly once in the file.
- `file_write` -  Write a file to the local filesystem. Overwrites the existing file if there is one.
- `dispatch_agent` - Launch a new agent that has access to read-only tools.
- `architect` - Your go-to tool for any technical or coding task. Analyzes requirements and breaks them down into clear, actionable implementation steps.
                Use this whenever you need help planning how to implement a feature, solve a technical problem, or structure your code.
- `code_critique` - Starts an interactive code review conversation that provides constructive feedback on your Clojure code.
- `clojure_edit_agent` - Specialized Clojure code editor that efficiently applies multiple code changes using structural editing tools.
- `scratch_pad` - A persistent scratch pad for storing structured data between tool calls.


### Resource Directories

- `/resources/prompts/`: System prompts for AI assistants
- `/resources/prompts/system/`: Core system prompts
- `/resources/agent/`: Agent-specific resources

### Documentation (that are also MCP resources)
- `./PROJECT_SUMMARY.md`: This document
- `./README.md`: Overview
- `/docs/development-plan.md`: The development plan for migrating from an older code called schedulingTBD to schedMCP.

## Dependencies and Versions

### Core Dependencies

- `org.clojure/clojure` (1.12.1): The Clojure language
- `io.modelcontextprotocol.sdk/mcp` (0.10.0): Model Context Protocol SDK
- `nrepl/nrepl` (1.3.1): Network REPL server for Clojure

1. **Factory Function Pattern**: The refactored architecture uses factory functions:
   - `make-tools`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of tools
   - `make-prompts`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of prompts
   - `make-resources`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of resources
   - All components created through `core/build-and-start-mcp-server`

2. **Multimethod Dispatch**: The tool system uses multimethods for extensibility:
   - `tool-name`: Determines the name of a tool
   - `tool-description`: Provides human-readable description
   - `tool-schema`: Defines the input/output schema
   - `validate-inputs`: Validates tool inputs
   - `execute-tool`: Performs the actual operation
   - `format-results`: Formats the results for the AI

3. **Core/Tool Separation**: Each tool follows a pattern:
   - `core.clj`: Pure functionality without MCP dependencies
   - `tool.clj`: MCP integration layer using the tool system

5. **Persistent State Management**: Through projects DB.

## Development Workflow Recommendations

1. **Setup and Configuration**:
   - Configure Claude Desktop with the Sched MCP server
   - Set up file system and Git integration if needed

## Recent Organizational Changes

**Use of Mount for starting everything**: Previously, we started the MCP loop separately.
