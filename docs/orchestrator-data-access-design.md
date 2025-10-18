# Orchestrator Data Access Design

## Overview

This document outlines a redesign of the orchestrator's interaction with the project and system databases and its expanded role in the schedMCP system. The orchestrator is evolving from a simple interview manager to a comprehensive AI agent responsible for:

1. **Pushing interviewing forward** - Selecting Discovery Schemas and delegating to LangGraph-based interviewing
2. **Validating understanding** - Creating diagrams and visualizations  
3. **Refining and testing MiniZinc models** - Building and testing scheduling solutions
4. **Mentoring** - Explaining what's happening to users
5. **Quizzing** - Checking user understanding through interactive questions
6. **Managing projects** - Starting new projects and resuming existing ones

This redesign moves from specialized tools for each operation to general-purpose data access using Datahike queries and tree navigation, giving the orchestrator the flexibility needed for these diverse responsibilities.

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

### Interviewing Delegation to LangGraph (This has been implemented!)

A key architectural shift: The orchestrator will delegate the interviewing loop to a LangGraph tool that:
- Takes a Discovery Schema and current ASCR as input
- Autonomously conducts the question/answer cycles with interviewees
- Returns a completed or refined ASCR
- Evaluates completeness according to the DS requirements

This frees the orchestrator to focus on the higher-level decisions listed in the Overview above rather than managing every question/answer interaction.

### Database Access Requirements

The orchestrator needs flexible access to **both databases**:

**Project Database:**
- Query ASCRs, conversations, messages for specific projects
- Navigate project tree structure
- Track interview progress and completeness

**System Database:**
- Access Discovery Schema definitions and metadata
- Query available Discovery Schemas
- Manage project lifecycle (create, archive, resume)
- Track cross-project patterns and learning

This dual-database access makes general-purpose query tools essential rather than specialized tools for each operation.

## Proposed Solution

### Core Principle

Provide the orchestrator with direct, flexible access to both project and system databases through:
1. **Schema transparency** - Give the orchestrator both DB schemas as resources
2. **Query capability** - Provide a tool to execute read-only Datalog queries on either database
3. **Tree navigation** - Offer tools like `resolve-db-id` for exploring database tree structures

### Benefits

1. **Flexibility** - Orchestrator can formulate queries for any question about project or system state
2. **Scalability** - One query tool + schema resources handle unlimited use cases
3. **Transparency** - Orchestrator sees and understands the actual data models
4. **Power** - Can combine `resolve-db-id` with helper functions like `conversation-exists?` to explore trees
5. **Simplicity** - Fewer tools to maintain, clearer separation of concerns
6. **Analogous to file operations** - Like providing `Read`, `Grep`, `Glob` instead of specialized tools for every file operation

## Orchestrator Responsibilities

### 1. Interview Management
- Query project state to determine which Discovery Schemas need attention
- Access Discovery Schemas from system DB
- Decide when to delegate interviewing to LangGraph
- Monitor ASCR completeness and quality
- Determine when enough information has been gathered

### 2. Validation and Visualization
- Generate diagrams to validate understanding (e.g., process flow, ORM diagrams)
- Present visualizations to users for confirmation
- Refine understanding based on feedback

### 3. MiniZinc Model Development
- Build MiniZinc models based on collected ASCRs
- Test models with sample data
- Refine models based on results
- Explain model decisions to users

### 4. Mentoring
- Explain current progress and next steps
- Clarify scheduling concepts as they arise
- Provide context for why certain information is needed

### 5. Knowledge Checking
- Quiz users on their understanding
- Verify comprehension of scheduling concepts
- Identify areas needing more explanation

### 6. Project Management
- **Starting projects:** Create new project DBs, initialize conversations
- **Resuming projects:** Query system DB to find existing projects
- **Project overview:** Show user what projects exist and their status
- **Archiving:** Mark completed or abandoned projects
- **Cross-project learning:** Identify patterns across multiple projects in system DB

## Implementation Requirements

### 1. Resources

**DB Schema Resource** ✅ (Implemented)
- Expose `db-schema-proj+` and `db-schema-sys+` from `src/schema.clj` as an MCP resource
- Document the project and system DB tree structures
- Explain entity relationships and attributes
- Explain Datahike basics (`:db/valueType`, `:db/cardinality`, `:db/unique`, `:db/doc`)

### 2. Tools

**Datalog Query Tool**
- Execute read-only Datalog queries against **either** project DB or system DB
- Parameters: `db_type` (`:project` or `:system`), `project_id` (if `:project`), `query`, optional `inputs`
- Returns query results
- Examples:
  - Query system DB for all Discovery Schemas
  - Query system DB for all projects with status
  - Query project DB for ASCRs in a specific project

**DB Tree Navigation Tool**
- Wrapper around `resolve-db-id` function
- Parameters: `db_type`, `project_id` (if `:project`), `db_id`, optional `keep_set`, `drop_set`
- Returns resolved entity tree structure
- Works on both project and system databases

**Helper Function Reference**
- Document functions that return DB IDs as starting points
- Examples: `conversation-exists?` returns DB id for navigation
- These can be exposed as simple query helpers

### 3. Orchestrator Guide Enhancement

The orchestrator needs comprehensive guidance on:

#### Query Patterns

**Project DB Queries:**
- How to check ASCR completion status
- How to find active/completed Discovery Schemas in a project
- How to determine conversation state
- How to count messages or filter by type

**System DB Queries:**
- How to list all available Discovery Schemas
- How to get Discovery Schema definitions
- How to find all projects (active, archived, etc.)
- How to query project metadata

#### Navigation Patterns

**Project DB Navigation:**
- project → conversations → messages
- project → ASCRs → specific DS data
- Finding specific entities by ID or attribute

**System DB Navigation:**
- Discovery Schema metadata
- Project listings and status
- Cross-project patterns

#### Common Operations

**Interview Management:**
- "Find all incomplete DS for conversation :process"
- "Get all messages pursuing a specific DS"
- "Check if budget is exhausted for current DS"
- "Determine next conversation to pursue"

**Project Management:**
- "List all active projects in system DB"
- "Find project by name or domain"
- "Get last modified date for all projects"
- "Check if project with name X already exists"

**Discovery Schema Access:**
- "Get all available Discovery Schemas from system DB"
- "Find DS by topic area (process, data, resources, optimality)"
- "Get DS definition for 'process/warm-up-with-challenges'"

#### Integration with Tools
- When to use query vs. delegation (e.g., LangGraph for interviewing)
- How DB queries inform high-level decisions
- Workflow examples:
  - Query incomplete ASCRs → Delegate DS to LangGraph → Monitor progress
  - Query collected data → Generate MiniZinc model → Test and refine
  - Query conversation history → Identify concepts to quiz user on

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

## Architectural Changes

### LangGraph Interview Tool

A new tool that encapsulates the interview loop:

**Input:**
- Discovery Schema ID
- Current ASCR (if any)
- Project ID and Conversation ID
- Budget/constraints

**Process:**
- Formulates questions based on DS and ASCR
- Interacts with interviewee (surrogate or human)
- Interprets responses into SCRs
- Combines SCRs into ASCR
- Evaluates completeness

**Output:**
- Updated ASCR
- Completeness status
- Conversation summary

### Tools for Other Responsibilities

**Diagram Generation:**
- Tool to create process flow diagrams
- Tool to create ORM diagrams
- Tool to render diagrams for user review

**MiniZinc Development:**
- Tool to generate MiniZinc from ASCRs
- Tool to test MiniZinc models
- Tool to explain model components

**Mentoring/Quizzing:**
- These may be handled by the orchestrator directly through prompts rather than specialized tools

## Next Steps

### Phase 1: DB Access (Current Focus)
1. ✅ Implement DB schema resource
2. Design detailed orchestrator guide with query examples
3. Implement Datalog query tool with read-only enforcement
4. Implement `resolve-db-id` wrapper tool
5. Create comprehensive example queries for common orchestration decisions

### Phase 2: LangGraph Integration (already implemented)
1. Design LangGraph interview tool specification
2. Implement interview delegation mechanism
3. Test with existing Discovery Schemas
4. Update orchestrator to use delegation instead of managing questions directly

### Phase 3: Expanded Capabilities
1. Implement diagram generation tools
2. Implement MiniZinc development tools
3. Design mentoring and quizzing workflows
4. Integrate all capabilities into unified orchestrator

### Phase 4: Testing and Refinement
1. Test complete workflow with surrogate experts
2. Refine based on user feedback
3. Document best practices for orchestration
4. Create example sessions demonstrating all capabilities

## References

- `src/sched_mcp/schema.clj` - Contains `db-schema-proj+` and `db-schema-sys+`
- `src/sched_mcp/project_db.clj` - Helper functions like `conversation-exists?`
- `src/sched_mcp/system_db.clj` - System DB operations and Discovery Schema access
- `src/sched_mcp/sutil.clj` - Contains `resolve-db-id` function

## Current State: 'Before' Tools

```clojure
[{:name "iviewr_formulate_question",  
  :description "Generate contextually appropriate interview questions based on Discovery Schema and current ASCR.
                This tool uses LLM reasoning to create natural questions that gather required information."}
 {:name "iviewr_interpret_response",  
  :description "Interpret a natural language answer into a Schema-Conforming Response (SCR). 
                This tool uses LLM reasoning to extract structured data from conversational responses."}
 {:name "sys_get_current_ds",         
  :description "Get the current Discovery Schema template and ASCR (Aggregated Schema-Conforming Response) for an active DS pursuit."}
 {:name "orch_get_next_ds",           
  :description "Get comprehensive Discovery Schema status and data for orchestration decisions. 
                Returns all available DS with their completion status, ASCRs, and interview objectives. 
                Use the MCP orchestrator guide to analyze this data and make recommendations."}
 {:name "orch_start_ds_pursuit",     
  :description "Begins working on a specific Discovery Schema. Initializes pursuit tracking and returns DS instructions."}
 {:name "orch_complete_ds",          
  :description "Marks a Discovery Schema pursuit as complete and stores the final ASCR."}
 {:name "orch_get_progress",         
  :description "Returns overall interview progress including completed Discovery Schemas and current phase."}
 {:name "sur_start_expert",          
  :description "Start an interview with a surrogate expert agent that simulates a domain expert in manufacturing. 
                The expert will answer questions about their manufacturing processes, challenges, and scheduling needs."}
 {:name "sur_answer",                
  :description "Get an answer from the surrogate expert. 
                The expert will respond as a domain expert would, providing specific details about their manufacturing processes and challenges."}]
```
