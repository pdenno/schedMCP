# SchedMCP Interview Workflow Guide

This guide provides the practical step-by-step workflow for conducting interviews using the schedMCP system.

## Overview

The schedMCP system conducts structured interviews to understand manufacturing scheduling challenges. Interviews involve three personas:
- **Orchestrator** (you) - Selects Discovery Schemas and manages interview flow
- **Interviewer** - Formulates questions and interprets responses
- **Interviewee** - Responds to questions (either human experts or AI surrogates)

## Key Concepts

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

### Step 1: Initialize the Interview

**For AI Surrogate Experts:**
```
sur_start_expert({
  domain: "<description of the manufacturing domain>"
})
```
Returns `project_id` - use this for all subsequent calls.

**For Human Experts:**
Create a project directly through the system (implementation varies).

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

**Decision Making:** See the separate "MCP Orchestrator Guide for Directing the Course of Interviews" resource for detailed guidance on selecting which DS to pursue.

### Step 3: Start DS Pursuit

```
orch_start_ds_pursuit({
  project_id: <pid>,
  conversation_id: <cid>,
  ds_id: "<selected-ds-id>",
  budget: 10  // optional: max questions for this DS
})
```

This initializes pursuit tracking and returns DS instructions.

### Step 4: Interview Cycle

Repeat this cycle until the DS is complete:

#### 4a. Formulate Question
```
iviewr_formulate_question({
  project_id: <pid>,
  conversation_id: <cid>,
  ds_id: "<current-ds-id>"
})
```

The interviewer analyzes the DS template, current ASCR, and conversation history to create a contextual question.

Returns: `{question: "...the formulated question..."}`

#### 4b. Get Response

**For Surrogate:**
```
sur_answer({
  project_id: <pid>,
  question: "<the question from 4a>"
})
```

**For Human:**
Present the question and collect their response through your interface.

Returns: `{answer: "...the expert's response..."}`

#### 4c. Interpret Response
```
iviewr_interpret_response({
  project_id: <pid>,
  conversation_id: <cid>,
  ds_id: "<current-ds-id>",
  question_asked: "<the question>",
  answer: "<the response>"
})
```

The interviewer extracts structured data (SCR) from the natural language response.

Returns: The SCR and potentially a completion status.

#### 4d. Check Completion

After each interpretation, check if the DS is complete. The system automatically:
- Stores the SCR with the message
- Combines the SCR with the existing ASCR
- Evaluates whether the DS has sufficient information

You can check current status with:
```
sys_get_current_ds({
  project_id: <pid>,
  conversation_id: <cid>
})
```

### Step 5: Complete DS Pursuit

When a DS has gathered sufficient information:
```
orch_complete_ds({
  project_id: <pid>,
  conversation_id: <cid>,
  final_notes: "Optional summary of what was learned"
})
```

### Step 6: Continue or Conclude

Return to Step 2 to select the next DS, or conclude if:
- All relevant DS are completed
- Sufficient information gathered for the scheduling solution

## Typical Interview Sequence

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

## Troubleshooting

### "No active DS pursuit found"
You need to call `orch_start_ds_pursuit` before formulating questions.

### "DS already completed"
Check with `orch_get_next_ds` - you may need to select a different DS.

### "Invalid conversation_id"
Must be one of: `:process`, `:data`, `:resources`, `:optimality`

### Questions seem off-topic
The interviewer uses the DS template and ASCR. If the ASCR is incomplete or contradictory, questions may be unclear. Review the current ASCR with `sys_get_current_ds`.

## Additional Resources

- **MCP Orchestrator Guide** - Detailed guidance on DS selection strategy
- **PROJECT_SUMMARY.md** - System architecture and component descriptions  
- **README.md** - Installation and configuration
- **Development Plan** - Current implementation status and future plans
