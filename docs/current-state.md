# schedMCP Current State Documentation

*Last Updated: January 2025*

## Overview

schedMCP is in active development, implementing the schedulingTBD Discovery Schema system using Model Context Protocol (MCP). This document captures the current state of implementation and our new development direction.

## New Direction: Core DS Flow First

**Important Update**: We are prioritizing getting the core Discovery Schema flow working before implementing table-based communication. This means:

1. **Phase 2.5 (NEW)**: Fix SCR storage and ASCR aggregation for basic text Q&A
2. **Phase 3**: Add table-based communication (after 2.5 is complete)
3. **Phase 4**: Advanced features (LangGraph, MCP-UI)

The key insight is that tables are an optimization - we need the basic DS mechanism working first.

## What's Working ✅

### Basic Interview Infrastructure
- **Interview Start**: `start_interview` tool successfully creates projects and initializes conversations
- **Question Retrieval**: `get_interview_context` returns current question and progress
- **Answer Submission**: `submit_answer` properly progresses through questions
- **Answer Retrieval**: `get_interview_answers` returns collected answers

### LLM-Based DS Tools
- **`formulate_question`**: Uses LLM to generate contextual questions from DS + ASCR
- **`interpret_response`**: Extracts structured data (SCR) from natural language answers
- Both tools implemented and calling LLM successfully

### Database Layer
- Datahike integration for persistent storage
- Project-specific databases created in `dbs/projects/`
- Schema supports projects, conversations, messages, pursuits, and DS data
- Clean project ID naming: `:craft-beer`, `:craft-beer-1`, `:sur-craft-beer`

### Discovery Schema Infrastructure
- 8 DS templates loaded from JSON:
  - Process: warm-up-with-challenges, flow-shop, job-shop (3 variants), scheduling-problem-type, timetabling
  - Data: ORM (Object-Role Modeling)
- DS loader (`ds_loader.clj`) working
- Basic combine logic (`ds_combine.clj`) implemented

### Orchestration Foundation
- Flow graph defining DS progression paths
- Priority-based DS selection logic
- DS pursuit tracking implemented

### Surrogate Expert System ✅
- Domain-specific expertise (craft-beer, plate-glass, metal-fabrication, etc.)
- Realistic interview behavior with consistent responses
- State management with database persistence
- Ready for testing DS flows

### MCP Integration
- Tool definitions following clojure-mcp patterns
- Registry system for all tools
- Proper error handling and structured responses

## What's Not Working Yet ❌

### Critical Gap: SCR/ASCR Pipeline
1. **SCR Storage**: `interpret_response` extracts SCRs but doesn't store them in messages
2. **ASCR Updates**: `combine-ds!` exists but isn't triggered after Q&A
3. **DS Completion**: No flow from completing one DS to starting the next

### Missing Features (Lower Priority)
- Table-based communication (postponed to Phase 3)
- MiniZinc generation
- Visualization of collected data
- Export/import of interview sessions

## Current Focus: Phase 2.5 Implementation

### What Needs to Be Fixed

```clojure
;; In interpret_response tool:
(defmethod tool-system/execute-tool :interpret-response
  [{:keys [_system-atom]} params]
  (let [scr (extract-scr-from-answer ...)]  ;; ✅ This works
    ;; ❌ MISSING: Store SCR with message
    ;; ❌ MISSING: Trigger ASCR update
    ;; ❌ MISSING: Check DS completion
    {:scr scr}))  ;; Currently only returns SCR
```

### Success Criteria for Phase 2.5

1. Complete warm-up DS through conversation
2. See SCRs stored in database messages
3. See ASCR properly aggregated
4. Transition to scheduling-problem-type DS
5. Complete second DS with proper data storage

## Current Architecture

```
src/
├── sched_mcp/
│   ├── core.clj              # MCP server setup
│   ├── interview.clj         # Interview management
│   ├── warm_up.clj           # Warm-up phase (deprecated)
│   ├── surrogate.clj         # Surrogate expert implementation
│   ├── llm.clj               # LLM integration
│   ├── ds_loader.clj         # DS JSON loading ✅
│   ├── ds_combine.clj        # SCR → ASCR aggregation ✅
│   ├── orchestration.clj     # DS flow management ✅
│   ├── tool_system.clj       # Multimethod tool framework
│   └── tools/
│       ├── registry.clj      # Central tool registry
│       ├── interviewer/
│       │   ├── core.clj      # formulate_question, interpret_response
│       │   └── advanced.clj  # DS management tools
│       ├── orchestrator/
│       │   └── core.clj      # get_next_ds, start_ds_pursuit
│       └── surrogate.clj     # Surrogate tools
```

## Testing the DS Flow (Phase 2.5)

### Test Script for Core DS Flow
```clojure
;; 1. Start interview
(start-interview {:project_name "DS Test Brewery"})
;; → project-id: :ds-test-brewery

;; 2. Start warm-up DS
(start-ds-pursuit {:project_id :ds-test-brewery
                   :conversation_id "conv-xxx"
                   :ds_id :process/warm-up-with-challenges})

;; 3. Get first question
(formulate-question {:project_id :ds-test-brewery
                     :conversation_id "conv-xxx"
                     :ds_id :process/warm-up-with-challenges})
;; → "What products do you make and what scheduling challenges?"

;; 4. Submit answer
(interpret-response {:project_id :ds-test-brewery
                     :conversation_id "conv-xxx"
                     :ds_id :process/warm-up-with-challenges
                     :answer "We make craft beer..."
                     :question_asked "What products..."})

;; 5. Check if SCR was stored (THIS SHOULD WORK)
(d/q '[:find ?scr
       :where [?m :message/scr ?scr]]
     @(connect-atm :ds-test-brewery))
;; Should return SCR data, not empty

;; 6. Check ASCR (THIS SHOULD WORK)
(get-ascr :ds-test-brewery :process/warm-up-with-challenges)
;; Should return aggregated data
```

## Development Timeline

### Week 0.5 (Current): Core DS Flow
- [ ] Fix SCR storage in interpret_response
- [ ] Implement ASCR triggering after Q&A
- [ ] Test warm-up → scheduling-problem-type flow
- [ ] Verify database storage
- [ ] Debug DS completion

### Week 1: Basic DS System
- [ ] Test all DS templates with text Q&A
- [ ] Implement DS completion detection
- [ ] Add transition logic between DSs
- [ ] Test with surrogate experts

### Week 2+: Table Communication
- [ ] Only after DS flow works perfectly
- [ ] Start with simple copy-paste tables
- [ ] Progress to more sophisticated UI

## Debugging Tips

### Check DS State
```clojure
;; List all DS pursuits
(d/q '[:find ?ds-id ?state
       :where
       [?p :pursuit/ds-id ?ds-id]
       [?p :pursuit/state ?state]]
     @(connect-atm project-id))

;; Check current ASCR
(get-ascr project-id :process/warm-up-with-challenges)

;; Check if SCRs are being stored
(d/q '[:find ?scr ?time
       :where
       [?m :message/scr ?scr]
       [?m :message/timestamp ?time]]
     @(connect-atm project-id))
```

### Common Issues
- "No SCRs found" - interpret_response not storing SCRs
- "ASCR empty" - combine-ds! not being triggered
- "DS won't complete" - completion logic not checking ASCR

## Summary

The project has a solid foundation with working LLM tools, DS infrastructure, and surrogate experts. The critical gap is the SCR/ASCR pipeline - we extract structured data but don't properly store or aggregate it.

**Our new pragmatic approach**: Fix the core DS flow first (Phase 2.5), verify it works with text Q&A, then add tables as an enhancement. This ensures we build on a working foundation rather than adding complexity to a broken system.
