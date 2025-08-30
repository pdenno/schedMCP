# schedMCP Current State Documentation

*Last Updated: January 2025*

## Overview

schedMCP is in early implementation phase, migrating the schedulingTBD Discovery Schema system to Model Context Protocol (MCP). This document captures the current state of implementation, what's working, and what needs attention.

## What's Working ✅

### Basic Interview Infrastructure
- **Interview Start**: `start_interview` tool successfully creates projects and initializes conversations
- **Question Retrieval**: `get_interview_context` returns current question and progress
- **Answer Submission**: `submit_answer` now properly progresses through questions (bug fixed)
- **Answer Retrieval**: `get_interview_answers` returns collected answers without null pointer errors (bug fixed)

### Database Layer
- Datahike integration for persistent storage
- Project-specific databases created in `dbs/projects/`
- Schema supports projects, conversations, messages, and EADS data

### MCP Integration
- Basic tool definitions following clojure-mcp patterns
- Four working tools: `start_interview`, `get_interview_context`, `submit_answer`, `get_interview_answers`
- Tools properly handle errors and return structured responses

### Warm-up Phase
- Simple warm-up interview with three questions implemented
- Questions about scheduling challenges, products/services, and additional context
- Progress tracking and completion detection

## What's Not Working Yet ❌

### Discovery Schema System
- No DS loading from JSON files yet
- No integration with Clojure implementation files (.clj)
- No combine logic for SCR → ASCR aggregation
- No support for Discovery Schemas beyond warm-up

### Orchestration
- No orchestrator tools implemented
- No DS recommendation engine
- No multi-interviewer support
- No budget tracking

### Advanced Features
- No MiniZinc generation
- No surrogate expert testing
- No visualization of collected data
- No export/import of interview sessions

## Current Architecture

```
src/
├── sched_mcp/
│   ├── core.clj          # MCP server setup (minimal)
│   ├── interview.clj     # Interview management
│   ├── warm_up.clj       # Warm-up phase implementation
│   ├── sutil.clj         # Database utilities
│   ├── util.clj          # General utilities
│   └── tools/
│       └── iviewr_tools.clj  # Interview MCP tools
```

## Known Issues

### Recently Fixed ✅
1. **Null pointer in `get_interview_answers`** - Fixed by adding defensive defaults
2. **`submit_answer` not progressing** - Fixed by auto-detecting current question

### Still Outstanding
1. **No DS JSON loading** - Need to implement DS loader
2. **Limited to warm-up** - Need to support full Discovery Schema system
3. **No state beyond EADS** - Need proper SCR/ASCR management
4. **No tool registration** - core.clj needs proper tool setup

## Testing the Current System

### Quick Test Sequence
```clojure
;; In REPL
(require '[sched-mcp.tools.iviewr-tools :as tools])

;; 1. Start interview
(tools/start-interview-tool {:project_name "Test Brewery" :domain "food-processing"})
;; Returns: {:project_id "test-brewery", :conversation_id "conv-xxx", ...}

;; 2. Get context
(tools/get-interview-context-tool {:project_id "test-brewery"})
;; Returns: current question

;; 3. Submit answer
(tools/submit-answer-tool {:project_id "test-brewery"
                          :conversation_id "conv-xxx"
                          :answer "We have bottlenecks at packaging"})

;; 4. Get all answers
(tools/get-interview-answers-tool {:project_id "test-brewery"
                                  :conversation_id "conv-xxx"})
```

### What Should Happen
1. Interview starts with warm-up phase
2. Three questions asked in sequence
3. After all required questions answered, interview marks as complete
4. Answers stored and retrievable

## Next Implementation Steps

### Immediate (Pre-Week 1-2 Tasks)
- [x] Fix critical bugs
- [x] Create DS file structure
- [x] Document current state (this document)
- [ ] Create DS loader spike
- [ ] Review existing DS examples
- [ ] Set up dev workflow helpers
- [ ] Create test checklist

### Near-term (Week 1-2)
- [ ] Implement DS JSON loader
- [ ] Create tool-system multimethod structure
- [ ] Add DS pursuit tracking
- [ ] Basic question generation from DS

### Medium-term (Week 3-4)
- [ ] Orchestrator tool implementation
- [ ] DS recommendation logic
- [ ] Budget tracking
- [ ] State management improvements

## Development Notes

### Database Location
- System DB: `dbs/system/sched-mcp-db`
- Project DBs: `dbs/projects/{project-name}/`

### Logging
- Agent logs: `logs/agent-log.edn`
- Use `alog!` for MCP tool logging
- Use `log!` for system logging

### Key Files to Understand
1. `examples/schedulingTBD/` - Reference implementation
2. `examples/clojure-mcp/` - MCP patterns to follow
3. `resources/discovery-schemas/` - DS templates (just copied)
4. `docs/agents/` - Agent descriptions for interviewers

## Debugging Tips

### Common Errors
- "No active conversation" - Check project_id and conversation_id are correct
- "Failed to connect to db" - Ensure project DB was created
- Null pointer - Usually missing EADS data, check DB state

### REPL Helpers
```clojure
;; Check project DB
(require '[datahike.api :as d])
(require '[sched-mcp.sutil :refer [connect-atm]])

;; Query all conversations
(d/q '[:find ?cid ?status
       :where [?c :conversation/id ?cid]
              [?c :conversation/status ?status]]
     @(connect-atm :test-brewery))

;; Check EADS data
(require '[sched-mcp.warm-up :as warm-up])
(warm-up/get-eads-data :test-brewery :conv-xxx)
```

## Summary

The foundation is in place with basic interview flow working. The main gap is the Discovery Schema system itself - we need to move beyond the simple warm-up phase to the full DS-based interview system. With bugs fixed and file structure ready, we're positioned to start implementing the core DS functionality.
