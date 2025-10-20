# schedMCP Current State Documentation

*Last Updated: October 2025*

## Overview

schedMCP implements a  Model Context Protocol (MCP) system for human/machine teaming (HMT) for the creation and maintenance of manufacturing production scheduling systems using the MiniZinc DSL.
It is a research prototype for investigating
1. methods of interviewing in HMT
2. methods of mentoring in HMT
3. use of DSLs in HTM
Currently, we only have the interviewing aspect implemented.

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

### LangGraph based Interviewing
- MCP tools for doing this, `iviewr_formulate_question` and `iviewr_interpret_response`, will be deprecated.

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

### Discovery Schema (DS) Instructions
DS Instructions are Clojure maps that contain (among other things) discovery schema (:DS) interview objectives (:interview-objective) and :budget-decrement, among other things.
The :DS key contains the actual discovery schema, an annotated example used by the interviewer to formulate questions; the response to these questions is interpreted into a Schema-Conforming Reponse (SCR).
DS Instructions can be found in `src/sched_mcp/interviewing/domain`

Here are the DS currently implemented:
- **Process Types**:
  - `process/warm-up-with-challenges` - Initial exploration and scheduling pain points
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
- MiniZinc model generation from ASCRs
- Integration of React-based UI from SchedulingTBD (an implementation of something similar to SchedMCP) for human expert use.

### Long Term
- Data collection over many interviews/scheduling systems


## Current Architecture

```
src/
├── sched_mcp/
│   ├── mcp_core.clj           # MCP server setup
│   ├── surrogate.clj         # Surrogate expert implementation
│   ├── llm.clj               # LLM integration
│   ├── tool_system.clj       # Multimethod tool framework
│   ├── interviewing/         # LangGraph-based subsystem for interviewing
|   |── tools/
│       ├── registry.clj      # Central tool registry
│       ├── iviewr/
│       │   ├── core.clj      # formulate_question, interpret_response
│       │   └── advanced.clj  # DS management tools
│       ├── orch/
│       │   └── core.clj      # Orchestration tools
│       └── surrogate.clj     # Surrogate expert tools
```

## Summary

The system is ready for comprehensive testing of basic operation. There is, however, a significant level of legacy code and legacy tests to be removed or updated.
