# schedMCP Current State Documentation

*Last Updated: August 29, 2025*

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
- Clean project ID naming: `:craft-beer`, `:craft-beer-1`, `:sur-craft-beer`

### MCP Integration
- Basic tool definitions following clojure-mcp patterns
- Working interview tools plus surrogate tools
- Tools properly handle errors and return structured responses

### Surrogate Expert System (Phase 2 Complete) ✅

#### Overview
The surrogate expert system successfully simulates domain experts for testing and development of Discovery Schemas without requiring human participants.

#### Key Capabilities
1. **Domain-Specific Expertise**
   - Surrogates can simulate experts in: craft-beer, plate-glass, metal-fabrication, food-processing, textiles
   - Each surrogate maintains consistent domain knowledge throughout the interview

2. **Realistic Interview Behavior**
   - Provides specific, consistent information about production processes, equipment, scheduling challenges
   - Automatically formats complex data in HTML tables when appropriate
   - Maintains conversation context across multiple questions

3. **State Management**
   - In-memory state for fast access during active interviews
   - Database persistence with proper attribution (`:message/from :surrogate`)
   - Clean project IDs using `:sur-<domain>` format

#### Example: Craft Beer Surrogate Interview
A typical surrogate expert (Cascade Craft Brewery) provides:
- **Production**: 100 HL/week (5 batches of 20 HL)
- **Products**: 8 beers (4 year-round, 4 seasonal)
- **Equipment**: 10 fermentation tanks, 8 conditioning tanks, 2 brite tanks
- **Process Times**: Fermentation (7-14 days), Conditioning (7-21 days)
- **Challenges**: Variable fermentation times, limited tank capacity, packaging bottlenecks

### LLM Integration
- Unified `llm.clj` supporting multiple providers
- Working with OpenAI GPT-4 and NIST_RCHAT (Meta)
- `query-llm` function compatible with schedulingTBD patterns

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
- No visualization of collected data
- No export/import of interview sessions

## Current Architecture

```
src/
├── sched_mcp/
│   ├── core.clj          # MCP server setup (minimal)
│   ├── interview.clj     # Interview management
│   ├── warm_up.clj       # Warm-up phase implementation
│   ├── surrogate.clj     # Surrogate expert implementation
│   ├── llm.clj           # LLM integration (OpenAI, NIST_RCHAT)
│   ├── system_db.clj     # System database management
│   ├── sutil.clj         # Database utilities
│   ├── util.clj          # General utilities
│   └── tools/
│       ├── iviewr_tools.clj  # Interview MCP tools
│       └── surrogate.clj     # Surrogate MCP tools
```

## Testing the Current System

### Surrogate Expert Test
```clojure
;; Start a surrogate interview
(surrogate/start-surrogate-interview
  {:domain :craft-beer
   :company-name "Mountain Peak Brewery"})
;; Creates project :sur-craft-beer (replaces if exists)

;; Ask questions
(surrogate/surrogate-answer-question
  {:project-id :sur-craft-beer
   :question "What are your main scheduling challenges?"})

;; View conversation history
(surrogate/get-conversation-history :sur-craft-beer)
```

### Quick Interview Test
```clojure
;; In REPL
(require '[sched-mcp.tools.iviewr-tools :as tools])

;; 1. Start interview
(tools/start-interview-tool {:project_name "Test Brewery" :domain "food-processing"})
;; Returns: {:project_id "test-brewery", :conversation_id "conv-xxx", ...}

;; 2-4. Get context, submit answers, retrieve answers...
```

## Project Naming Convention

- **Surrogate Projects**: `:sur-<domain>` (e.g., `:sur-craft-beer`)
  - Always replaces existing project with same ID
  - Ensures only one surrogate per domain at a time

- **Human Projects**: Simple incremental naming
  - First project: `:craft-beer`
  - Subsequent projects: `:craft-beer-1`, `:craft-beer-2`, etc.

## Next Implementation Steps

### Immediate - Phase 3 (Table-Based Communication)
- [ ] Enhanced table parsing and validation
- [ ] Complex multi-table scenarios
- [ ] Interactive table editing workflows

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
- System DB: `test/dbs/system/`
- Project DBs: `test/dbs/projects/{project-id}/`

### Logging
- Agent logs: `logs/agent-log.edn`
- Use `alog!` for MCP tool logging
- Use `log!` for system logging
- Surrogate responses logged with 🟠 orange indicator

### Key Files to Understand
1. `examples/schedulingTBD/` - Reference implementation
2. `examples/clojure-mcp/` - MCP patterns to follow
3. `resources/discovery-schemas/` - DS templates
4. `docs/agents/` - Agent descriptions for interviewers

## Debugging Tips

### Common Errors
- "No active conversation" - Check project_id and conversation_id are correct
- "Failed to connect to db" - Ensure project DB was created
- Null pointer - Usually missing EADS data, check DB state

### REPL Helpers
```clojure
;; Check all projects
(sys-db/list-projects)
;; => [:craft-beer :craft-beer-1 :sur-craft-beer :sur-plate-glass]

;; Check surrogate sessions
(keys @surrogate/expert-sessions-atom)

;; Query conversation messages
(d/q '[:find ?from ?type ?content
       :where
       [?e :message/from ?from]
       [?e :message/type ?type]
       [?e :message/content ?content]]
     @(connect-atm :sur-craft-beer))
```

## Summary

The foundation is solid with basic interview flow working and surrogate experts providing realistic domain expertise. The surrogate system demonstrates we can effectively simulate manufacturing experts for automated testing. The main gap is the Discovery Schema system itself - we need to move beyond the simple warm-up phase to the full DS-based interview system. With Phase 2 (Surrogate Experts) complete, we're ready for Phase 3 (Table-Based Communication) and then full DS implementation.
