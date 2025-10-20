This guide provides the practical step-by-step workflow for conducting interviews using the schedMCP system.

## Overview

The schedMCP system conducts structured interviews to understand manufacturing scheduling challenges. Interviews involve three personas:
- **Orchestrator** (you, the LLM running MCP) - Selects Discovery Schemas and delegates interviewing with them to a LangGraph-based tool.
- **Interviewer** - Formulates questions and interprets responses
- **Interviewee** - Responds to questions (either human experts or AI surrogates)

## Key Concepts

### Project Database is Central to Your Work 

Your job is to orchestrate interactions between the interviewees and the system, collecting their requirements, building a solution, and explaining it.
The intention of the system design is that everything you need to do this job is stored in the project database. 
<=========================================================


### Discovery Schema (DS)
Structured templates that guide information collection on specific topics. Examples:
- `process/warm-up-with-challenges` - Initial exploration
- `process/flow-shop` - Flow-shop scheduling details
- `data/orm` - Object-Role Modeling

### Schema-Conforming Response (SCR)
Structured data extracted from a single interview response that conforms to the DS template.

### Aggregated Schema-Conforming Response (ASCR)
Combined SCRs representing the complete state of information collected for a DS.

### Conversation ID
Interviews are organized by topic. The `conversation_id` is one of:
- `:process` - How they produce products/services
- `:data` - Data structures and relationships
- `:resources` - Physical resources and capabilities
- `:optimality` - What makes a "good" schedule

## Complete Interview Workflow

<### Step 1: Initialize the the project


When the user asks to run an interview and provides a domain (e.g. plate glass), you start a surrogate expert in that domain that will be the **Interviewee**.

```
sur_start_expert({
  domain: "<description of the manufacturing domain>"
})
```
Returns `project_id` - use this for all subsequent calls.

### Step 2: Get the Next Discovery Schema

```
orch_get_next_ds({
  project_id: <pid>,
  conversation_id: <cid>
})
```

This returns:
- Available Discovery Schemas with their status
- Current ASCRs (what's been learned)
- Recommendations for what to pursue next

**Decision Making:** See  "MCP Orchestrator Guide for Directing the Course of Interviews" resource for detailed guidance on selecting which DS to pursue.

### Step 3: Start DS Pursuit

```
orch_start_ds_pursuit({
  project_id: <pid>,
  conversation_id: <cid>,
  ds_id: "<selected-ds-id>",
  budget: 10  // optional: max questions for this DS
})
```

The interviewer extracts structured data (SCR) from the natural language response.

### Starting Fresh (No Prior Work)

1. Start with `process/warm-up-with-challenges` (conversation: `:process`)
2. Then `process/scheduling-problem-type` (conversation: `:process`)
3. Based on problem type:
   - Flow-shop → `process/flow-shop`
   - Job-shop → `process/job-shop` variants
   - Timetabling → `process/timetabling`
4. Transition to data: `data/orm` (conversation: `:data`)
5. Eventually: resources and optimality topics

### Resuming Existing Work

1. Call `orch_get_progress({project_id: <pid>})` to see overall status
2. Review completed DS and current ASCRs
3. Select next appropriate DS based on what's missing

## Parameter Quick Reference

| Parameter | Type | Values/Examples |
|-----------|------|-----------------|
| `project_id` | keyword | `:project-123`, returned from `sur_start_expert` |
| `conversation_id` | keyword | `:process`, `:data`, `:resources`, `:optimality` |
| `ds_id` | string | `"process/warm-up-with-challenges"`, `"data/orm"` |
| `budget` | integer | `10` (optional, default varies) |
| `domain` | string | `"craft beer brewing"`, `"automotive parts"` |

## State Management

All interview state is persisted in the project database:
- Individual messages with their SCRs
- Current ASCR for each DS
- DS pursuit status (not-started, active, in-progress, completed)
- Conversation history

State is maintained across:
- Multiple question/answer cycles
- DS transitions
- System restarts
- Different conversation topics


## Additional Resources

- **MCP Orchestrator Guide** - Detailed guidance on DS selection strategy
- **PROJECT_SUMMARY.md** - System architecture and component descriptions  
- **README.md** - Installation and configuration


