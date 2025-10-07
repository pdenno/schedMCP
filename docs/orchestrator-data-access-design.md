# Orchestrator Data Access Design

## Overview

This document outlines a redesign of the orchestrator's interaction with the project database, moving from specialized tools for each operation to a general-purpose data access approach using Datahike queries and tree navigation.

## Motivation

### Problems with Current Approach

1. **Tool Proliferation** - Every orchestration decision requires a new specialized tool (`orch_complete_ds`, `orch_check_X`, `orch_update_Y`, etc.)
2. **Rigidity** - Tools return pre-formatted data structures, limiting what the orchestrator can discover or explore
3. **Scalability Issues** - The fully developed system could require 100s of simple status-checking and state-manipulation tools
4. **Hidden Data Model** - The orchestrator cannot freely explore the project structure; it's constrained to pre-built queries

### Current Tool Examples

Simple tools like `orch_complete_ds` only mark status in the DB. While necessary, having many such tools is:
- Hard to maintain
- Difficult to document comprehensively
- Limiting to the orchestrator's decision-making capabilities

## Proposed Solution

### Core Principle

Provide the orchestrator with direct, flexible access to the project database through:
1. **Schema transparency** - Give the orchestrator the DB schema as a resource
2. **Query capability** - Provide a tool to execute read-only Datalog queries
3. **Tree navigation** - Offer tools like `resolve-db-id` for exploring the project tree structure

### Benefits

1. **Flexibility** - Orchestrator can formulate queries for any question about project state
2. **Scalability** - One query tool + schema resource handles unlimited use cases
3. **Transparency** - Orchestrator sees and understands the actual data model
4. **Power** - Can combine `resolve-db-id` with helper functions like `conversation-exists?` to explore the tree
5. **Simplicity** - Fewer tools to maintain, clearer separation of concerns
6. **Analogous to file operations** - Like providing `Read`, `Grep`, `Glob` instead of specialized tools for every file operation

## Implementation Requirements

### 1. Resources

**DB Schema Resource**
- Expose `db-schema-proj+` from `src/schema.clj` as an MCP resource
- Document the project tree structure
- Explain entity relationships and attributes

### 2. Tools

**Datalog Query Tool**
- Execute read-only Datalog queries against the project DB
- Parameters: `project_id`, `query`, optional `inputs`
- Returns query results

**DB Tree Navigation Tool**
- Wrapper around `resolve-db-id` function
- Parameters: `project_id`, `db_id`, optional `keep_set`, `drop_set`
- Returns resolved entity tree structure

**Helper Function Reference**
- Document functions that return DB IDs as starting points
- Examples: `conversation-exists?` returns DB id for navigation
- These can be exposed as simple query helpers

### 3. Orchestrator Guide Enhancement

The orchestrator needs comprehensive guidance on:

#### Query Patterns
- How to check ASCR completion status
- How to find active/completed Discovery Schemas
- How to determine conversation state
- How to count messages or filter by type

#### Navigation Patterns
- project → conversations → messages
- project → ASCRs → specific DS data
- Finding specific entities by ID or attribute

#### Common Operations
- "Find all incomplete DS for conversation :process"
- "Get all messages pursuing a specific DS"
- "Check if budget is exhausted for current DS"
- "Determine next conversation to pursue"

#### Integration with Existing Tools
- When to use query vs. specialized tools (e.g., `iviewr_formulate_question`)
- How DB queries inform orchestration decisions
- Workflow examples combining queries with interviewer tools

## Key Considerations

### Documentation Quality

The success of this approach depends on **excellent examples** in the orchestrator guide showing:
- Concrete Datalog query syntax for common scenarios
- Step-by-step navigation patterns
- How to interpret query results
- Error handling and edge cases

### Read-Only Safety

- Query tool must be strictly read-only
- State modifications still use specialized tools (when needed)
- Clear separation between query/inspection and mutation

### Performance

- Queries should be efficient; provide guidance on query optimization
- Consider caching frequently accessed data
- Document any performance implications of tree resolution with `keep-set`/`drop-set`

## Next Steps

1. Design detailed orchestrator guide with query examples
2. Implement DB schema resource
3. Implement Datalog query tool with read-only enforcement
4. Implement `resolve-db-id` wrapper tool
5. Create comprehensive example queries for common orchestration decisions
6. Update existing orchestrator workflow documentation
7. Test with real interview scenarios

## References

- `src/schema.clj` - Contains `db-schema-proj+`
- `src/sched_mcp/project_db.clj` - Helper functions like `conversation-exists?`
- `src/sched_mcp/sutil.clj` - Contains `resolve-db-id` function
