# schedMCP Current State Documentation

*Last Updated: January 2025*

## Overview

schedMCP implements the schedulingTBD Discovery Schema system using Model Context Protocol (MCP). The system uses an orchestrator-driven approach to dynamically conduct interviews based on current knowledge state.

## Core Architecture

The system operates through three main components:

### 1. Orchestrator
- Analyzes current knowledge state (ASCRs)
- Selects next Discovery Schema to pursue
- Determines when sufficient information has been gathered
- Manages interview flow dynamically

### 2. Interviewer
- Executes Discovery Schema pursuits
- Generates contextual questions using LLMs
- Interprets responses into structured data (SCRs)
- Updates aggregated knowledge (ASCRs)

### 3. Discovery Schema System
- 8 DS templates loaded from JSON
- Each DS defines what information to gather
- SCRs (Schema-Conforming Responses) capture individual answers
- ASCRs (Aggregated SCRs) represent cumulative knowledge

## What's Working ✅

### Complete DS Flow Pipeline
1. **SCR Storage**: Responses are extracted and stored with messages
2. **ASCR Building**: SCRs are automatically aggregated after each response
3. **DS Completion**: System detects when a DS has gathered sufficient information
4. **Dynamic Progression**: Orchestrator selects next DS based on current knowledge

### LLM-Based Tools
- **`iviewr_formulate_question`**: Generates natural questions from DS + ASCR context
- **`iviewr_interpret_response`**: Extracts structured SCRs from conversational answers
- Both tools use contextual prompting for domain-appropriate interactions

### Database Layer
- Datahike integration for persistent storage
- Project-specific databases created in `dbs/projects/`
- Schema supports projects, conversations, messages, pursuits, and DS data
- Clean project ID naming: `:craft-beer`, `:craft-beer-1`, `:sur-craft-beer`

### Discovery Schema Templates
Available DS templates (found in `src/tools/iviewr/domain` and available from the system DB by calling `sdb/get-discovery-schema-JSON`).
- **Process Types**:
  - `process/warm-up-with-challenges` - Initial exploration and pain points
  - `process/flow-shop` - Sequential production flow
  - `process/job-shop` (3 variants) - Flexible routing workflows
  - `process/scheduling-problem-type` - Problem classification
  - `process/timetabling` - Time-based scheduling
- **Data Modeling**:
  - `data/ORM` - Object-Role Modeling for domain relationships

### Orchestration System
- Flow graph defining DS progression paths
- Priority-based DS selection considering:
  - Current knowledge gaps
  - DS dependencies
  - Interview objectives
- Automatic progression when DS completes

### Surrogate Expert System ✅
- Simulates domain experts for testing
- Supports multiple domains:
  - craft-beer, plate-glass, metal-fabrication
  - textiles, food-processing, automotive
- Maintains consistent persona and knowledge
- Generates realistic, contextual responses

## Future Enhancements

### Near Term
- Additional Discovery Schema templates
- Table-based communication for complex data
- MiniZinc model generation from ASCRs
- Export/import of interview sessions

### Long Term
- LangGraph integration for advanced orchestration
- Multi-user collaborative interviews
- Real-time visualization of gathered knowledge
- Integration with external scheduling systems

## Current Architecture

```
src/
├── sched_mcp/
│   ├── core.clj              # MCP server setup
│   ├── surrogate.clj         # Surrogate expert implementation
│   ├── llm.clj               # LLM integration
│   ├── tool_system.clj       # Multimethod tool framework
│   └── tools/
│       ├── registry.clj      # Central tool registry
│       ├── iviewr/
│       │   ├── core.clj      # formulate_question, interpret_response
│       │   └── advanced.clj  # DS management tools
│       ├── orch/
│       │   └── core.clj      # Orchestration tools
│       └── surrogate.clj     # Surrogate expert tools
```

## Testing the System

### Complete Interview Flow Test
```clojure
;; 1. Start surrogate expert
(def project-id (:project_id (sur-start-expert {:domain "craft-beer"})))

;; 2. Check available DS options
(orch-get-next-ds {:project_id project-id :conversation_id "main"})

;; 3. Start recommended DS
(orch-start-ds-pursuit {:project_id project-id
                        :conversation_id "main"
                        :ds_id "process/warm-up-with-challenges"})

;; 4. Run Q&A cycles
(let [q (iviewr-formulate-question {:project_id project-id
                                    :conversation_id "main"
                                    :ds_id "process/warm-up-with-challenges"})
      a (sur-answer {:project_id project-id :question (:question q)})]
  (iviewr-interpret-response {:project_id project-id
                              :conversation_id "main"
                              :ds_id "process/warm-up-with-challenges"
                              :answer (:answer a)
                              :question_asked (:question q)}))

;; 5. Check progress
(orch-get-progress {:project_id project-id})

;; 6. When DS completes, get next recommendation
(orch-get-next-ds {:project_id project-id :conversation_id "main"})
```

### Debugging Commands
```clojure
;; Check current ASCR
(get-ascr project-id :process/warm-up-with-challenges)

;; View all pursuits
(d/q '[:find ?ds-id ?state
       :where
       [?p :pursuit/ds-id ?ds-id]
       [?p :pursuit/state ?state]]
     @(connect-atm project-id))

;; Check stored SCRs
(d/q '[:find ?scr ?time
       :where
       [?m :message/scr ?scr]
       [?m :message/timestamp ?time]]
     @(connect-atm project-id))
```

## Summary

The schedMCP system successfully implements an orchestrator-driven interview system using Discovery Schemas. The orchestrator dynamically selects appropriate schemas based on current knowledge, while the interviewer uses LLMs to conduct natural conversations that build structured understanding of manufacturing scheduling domains.

The system is ready for comprehensive testing across different manufacturing domains using the surrogate expert system.
