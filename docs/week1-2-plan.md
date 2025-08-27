# Week 1-2 Implementation Plan

## Core Discovery Schema Tools

### Overview
We need to implement the core tools that enable LLM-based interviewing using Discovery Schemas. These tools will:
1. Load both JSON templates and Clojure implementation files
2. Track DS pursuit state
3. Generate questions based on DS + ASCR
4. Interpret responses into SCRs
5. Aggregate SCRs into ASCRs

### Task Breakdown

#### 1. Tool System Setup (Day 1)
- [ ] Create `tool_system.clj` with multimethod definitions
- [ ] Set up tool registration pattern
- [ ] Create base tool configuration

#### 2. Enhanced DS Loader (Day 1-2)
- [ ] Load matching .clj files for DS implementations
- [ ] Extract combine-ds! functions
- [ ] Extract ds-complete? functions
- [ ] Cache loaded DS data

#### 3. State Management (Day 2)
- [ ] Design ASCR storage schema
- [ ] Implement SCR storage with messages
- [ ] Create DS pursuit tracking

#### 4. Interviewer Tools (Day 3-4)
- [ ] `formulate_question` - LLM-based question generation
- [ ] `interpret_response` - Convert NL to SCR
- [ ] `get_current_ds` - Retrieve active DS + ASCR

#### 5. Basic Orchestrator Tools (Day 4-5)
- [ ] `get_next_ds` - Recommend next DS
- [ ] `start_ds_pursuit` - Initialize DS work
- [ ] `complete_ds` - Finalize and store ASCR

#### 6. Integration & Testing (Day 5-6)
- [ ] Connect tools to MCP server
- [ ] Test with process interviewer agent
- [ ] Run full warm-up → scheduling-type flow

### Design Decisions

1. **LLM Integration**: Use the pattern from schedulingTBD - pass DS + instructions to LLM
2. **State Storage**: Keep ASCRs in Datahike with proper schema
3. **Tool Dispatch**: Use multimethod pattern from clojure-mcp
4. **Error Handling**: Graceful degradation when DS operations fail

### Success Criteria
- Can load any DS with its implementation
- Can generate contextual questions from DS
- Can interpret responses into valid SCRs
- Can aggregate SCRs using DS-specific logic
- Can complete a multi-DS interview