# schedMCP Development Guidelines

## Overview

This document outlines the development practices, architectural decisions, and coding standards for the schedMCP project. schedMCP implements schedulingTBD's Discovery Schema system using Model Context Protocol (MCP) tools.

## Dependencies

### Core Principles
- Keep minimal external dependencies
- Prefer copying and adapting code over adding dependencies
- Document the source of any borrowed code

### Specific Guidelines
- **Do not add clojure-mcp as a dependency** - While we borrow design patterns and code structures from `examples/clojure-mcp`, we maintain independence by copying and adapting rather than referencing
- When borrowing code, add attribution comments:
  ```clojure
  ;; Adapted from clojure-mcp.tool-system
  ;; We copy rather than depend on clojure-mcp to maintain independence
  ```
- Focus dependencies on core scheduling domain needs

## Architecture Decisions

### Discovery Schema (DS) System
- The term "EADS" is deprecated - use "Discovery Schema" or "DS" instead
- Each DS has two components:
  1. **JSON file** - Human-readable template with instructions and annotations
  2. **Clojure file** - Implementation with combine logic, validation specs, and completion checks

### MCP Tool Design
- Tools must be **stateless** - all state lives in the database
- Each tool call receives complete context (DS template + current ASCR)
- Tools operate as pure functions: `(DS, ASCR, new_input) → (action, updated_ASCR)`
- Use multimethod dispatch pattern borrowed from clojure-mcp

### State Management
- Individual SCRs stored with each conversation response
- Aggregated SCR (ASCR) maintained separately in database
- Discovery Schema-specific combine logic merges new SCRs into ASCR
- Completion tracked using DS-specific logic

## Code Organization

### Directory Structure
```
schedMCP/
├── src/
│   ├── sched-mcp/
│   │   ├── core.clj               # MCP server setup
│   │   ├── tool_system.clj        # Multimethod definitions
│   │   ├── db.clj                 # Database operations
│   │   |── tools/                 # Individual tool implementations
│   │       ├── domain/            # Top-level directory for discovery schema
│   │               ├── process/   # Discovery schema for interviewing about processes
│   │               ├── data/      # Discovery schema for interviewing about data
│   │               ├── resources  # Discovery schema for interviewing about resources (currently empty)
│   │               ├── optimality # Discovery schema for interviewing about what is sought in good schedules (currently empty)
│   │       ├── orchestrator/      # Orchestration tools
│   │       ├── interviewer/       # Interview tools
│   │       └── utility/           # Helper tools
└── test/
```
### Discovery Schema
- When `mount/start` (the tool used to load/reload the project into the REPL environment) is executed the system evalutes the `.clj` files
  in the `src/sched-mcp/tools/domain` directory and compares the discovery schema object against created against the one in the system DB.
  If it is different, the system DB is automatically updated.
- Whenever a discovery schema is required, it can be obtained by running `(sdb/get-discovery-schema-JSON).

### Naming Conventions
Follow Clojure conventions with these additions:
- **Boolean variables**: End with `?` (e.g., `complete?`, `exhausted?`)
- **Mutating functions**: End with `!` (e.g., `update-db!`, `combine-ds!`)
- **Discovery Schema IDs**: Use keywords like `:process/flow-shop`
- **Tool names**: Use underscores for MCP compatibility (e.g., `get_next_ds`)

## Testing Strategy

### Unit Testing
- Test individual tools with mock data
- Verify DS combine logic independently
- Test validation and completion functions

### Integration Testing
- Use surrogate experts from schedulingTBD
- Test complete interview flows
- Verify state persistence across tool calls

### Manual Testing
- Test with Claude Desktop or other MCP clients
- Verify interview flow and orchestration
- Check DS completion and ASCR generation

## Development Workflow

### Before Starting
1. Fix any existing bugs (check current state in issue tracker)
2. Review relevant Discovery Schemas in `examples/schedulingTBD`
3. Understand both JSON and Clojure components of target DS

### Implementation Steps
1. Start with simple tools and basic functionality
2. Test thoroughly before adding complexity
3. Implement one Discovery Schema completely before adding others
4. Use existing patterns from schedulingTBD and clojure-mcp

### Code Review Checklist
- [ ] Tools are stateless
- [ ] Proper error handling with informative messages
- [ ] Attribution comments for borrowed code
- [ ] Tests for new functionality
- [ ] Documentation updated
- [ ] No new dependencies without team discussion

## Documentation Standards

### Code Documentation
- Use docstrings for all public functions
- Document tool parameters and return values
- Include examples in docstrings where helpful

### Design Documentation
- Update `mcp-discovery-schema-design.md` for architectural changes
- Document DS-specific logic in respective Clojure files
- Keep `CLAUDE.md` updated with AI-relevant instructions

## Debugging Tips

### Common Issues
1. **Null pointer in tool execution** - Check database initialization
2. **Interview not progressing** - Verify DS completion logic
3. **SCR not merging** - Check combine function and ID fields
4. **Tool not found** - Verify tool registration in core.clj

### Debugging Tools
- Use `^:diag` metadata for REPL-only debug atoms
- Liberal use of logging at INFO level
- Test tools individually before integration

## Future Considerations

As the project evolves:
- Consider performance optimizations for large ASCRs
- Plan for DS versioning and migration
- Design for multi-user/collaborative interviews
- Think about MiniZinc generation integration

## Contributing

When contributing to schedMCP:
1. Review these guidelines
2. Check existing issues and discussions
3. Test thoroughly with realistic scenarios
4. Update documentation as needed
5. Submit focused, well-documented changes

Remember: The goal is to create a flexible, maintainable system that combines Discovery Schema domain expertise with LLM reasoning capabilities.
