# Sched MCP Project Summary

## Project Overview

Sched MCP is a Model Context Protocol (MCP) server for creating manufacturing scheduling systems using MiniZinc and combinatorial optimization solvers.
It is research software about human/AI teaming on long-running and complex endeavors (where production scheduling is that endeavor).
When completed (it is in development right now) it will mentor users in the use of the MiniZinc DSL, and help users validate their solution

The project allows AI assistants to:
- Interview users about their scheduling challenge.
- Create and refine a MiniZinc solution (future)
- Integrate with their production system to run the scheduling solution they have created.

## Key File Paths and Descriptions

- `/src/schedmcp/examples/schedulingTBD`: The base directory of the project schedulingTBD, which inspired this MCP-based version.
- `/src/schedmcp/examples/clojure-mcp`: A well-designed MCP server implementing an AI programming assistant for Clojure; we hope to steal from it!

### Core System Files

### Tool Implementations

#### Active Tools (used in main.clj)


#### Unused Tools (moved to other_tools/)

### Example Main Files

### Resource Directories

- `/resources/prompts/`: System prompts for AI assistants
- `/resources/prompts/system/`: Core system prompts
- `/resources/agent/`: Agent-specific resources

### Documentation

- `/README.md`: Documentation overview
- `/doc/mcp-discovery-schema-design.md`: The development plan for migrating from an older code called schedulingTBD to schedMCP.

## Dependencies and Versions

### Core Dependencies

- `org.clojure/clojure` (1.12.1): The Clojure language
- `io.modelcontextprotocol.sdk/mcp` (0.10.0): Model Context Protocol SDK
- `nrepl/nrepl` (1.3.1): Network REPL server for Clojure

### AI Integration Dependencies

- `dev.langchain4j/langchain4j` (1.0.1): Java library for LLM integration
- `dev.langchain4j/langchain4j-anthropic` (1.0.1-beta6): Anthropic-specific integration
- `dev.langchain4j/langchain4j-google-ai-gemini` (1.0.1-beta6): Google Gemini integration
- `dev.langchain4j/langchain4j-open-ai` (1.0.1): OpenAI integration
- `pogonos/pogonos` (0.2.1): Mustache templating for prompts

### Additional Dependencies

- `org.clojure/data.json` (2.5.1): JSON parsing and generation
- `org.clojure/tools.cli` (1.1.230): Command line argument parsing

## Configuration System

### Configuration Location
```
your-project/
├── .sched-mcp/
│   └── config.edn
├── src/
└── deps.edn
```

### Configuration Options

The following tools are available in the default configuration (`mcp-core.clj`):  THIS MIGHT BE INCORRECT.

### Read-Only Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|

### Minizinc Code Evaluation

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|

### Minizinc File Editing Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|

### Introspection

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|

### Agent Tools (Configuration-Based)

Default agent tools are automatically created through the agent-tool-builder system. These can be customized via `:tools-config` or completely replaced via `:agents` configuration:

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|

### Experimental Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `scratch_pad` | A persistent scratch pad for storing structured data between tool calls | Task tracking, intermediate results, inter-agent communication |

## Tool Examples

### Scratch Pad - Persistent Data Storage

```clojure
scratch_pad:
  op: set_path
  path: ["todos", 0]
  value: {task: "Write tests", done: false}
  explanation: Adding first task
  Output: Stored value at path ["todos", 0]

scratch_pad:
  op: get_path
  path: ["todos", 0]
  explanation: Checking first task
  Output: Value at ["todos", 0]: {task: "Write tests", done: false}
```

## Architecture and Design Patterns

### Core Architecture Components

1. **MCP Server**: Entry point that exposes tools to AI assistants

### Key Implementation Patterns

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

This project summary is designed to provide AI assistants with a quick understanding of the Sched MCP project structure and capabilities, enabling more effective assistance with minimal additional context.
The project continues to evolve with improvements focused on making it easier to create custom MCP servers while maintaining compatibility with a wide range of LLMs.
