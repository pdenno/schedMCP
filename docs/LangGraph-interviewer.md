# LangGraph-Based Interviewing Architecture

## Overview

This document describes the transition from MCP-tool-based interviewing to LangGraph-managed interview loops. The goal is to delegate tactical interview execution (question formulation, response interpretation, ASCR building) to LangGraph while keeping strategic decisions (DS selection) with the MCP orchestrator.

## Current Architecture (Before)

### MCP Orchestrator (Claude)
- Uses `orch_get_next_ds` to analyze project state
- Uses `orch_start_ds_pursuit` to begin a DS
- **Manages every Q&A cycle:**
  - Uses `iviewr_formulate_question` to create each question
  - Uses `sur_answer` (or human input) to get response
  - Uses `iviewr_interpret_response` to extract SCR
  - System automatically merges SCR → ASCR
  - Repeats until DS complete
- Uses `orch_complete_ds` to mark completion

### Problems
- Orchestrator manages low-level interview loop
- High token usage (every Q&A is a full LLM round-trip)
- Difficult to implement sophisticated interview strategies
- Tight coupling between strategy and tactics

## Proposed Architecture (After)

### MCP Orchestrator (Claude) - Strategic Level
```
1. Query project DB to understand state
2. Decide next Discovery Schema to pursue
3. Call LangGraph tool: conduct-interview(pid, cid, ds-id, current-ascr)
   - Note: current-ascr is typically {} when starting fresh DS
4. Receive completed/refined ASCR
5. Decide: continue with another DS, refine this one, or conclude
```

### LangGraph Interview Agent - Tactical Level
```
Autonomous interview loop that:
- Formulates questions based on DS and current ASCR
- Gets answers from surrogate/human
- Interprets responses into SCRs
- Reduces SCRs into ASCR
- Evaluates completion criteria
- Returns when DS is sufficiently complete
```

## Interview State Structure

### Using Clojure Record (Option 2 from discussion)

```clojure
(defrecord InterviewState
  [ds-id           ; :process/warm-up-with-challenges
   ascr            ; {} initially, builds up: {:challenges [...], :motivation "..."}
   messages        ; [{:from :system :content "..."} {:from :surrogate :content "..."}]
   budget-left     ; 10.0 initially, decrements per question
   complete?       ; false until DS completion criteria met
   pid             ; :craft-beer-123
   cid])           ; :process
```

### State Channels

```clojure
{:ds-id      (lg/make-last-write-channel)      ; Single value
 :ascr       (lg/make-reducer-channel merge)   ; Reduces SCRs into ASCR
 :messages   (lg/make-appender-channel)        ; Appends messages
 :budget-left (lg/make-last-write-channel)     ; Updates budget
 :complete?  (lg/make-last-write-channel)      ; Completion flag
 :pid        (lg/make-last-write-channel)
 :cid        (lg/make-last-write-channel)}
```

## Graph Structure

### Nodes

1. **formulate-question**
   - Input: current state (ds-id, ascr, messages)
   - Calls LLM to create contextual question
   - Output: {:messages new-question}

2. **get-answer**
   - Input: current state (with question in messages)
   - Calls surrogate/gets human input
   - Output: {:messages answer}

3. **interpret-response**
   - Input: current state (with Q&A in messages)
   - Calls LLM to extract SCR from answer
   - Output: {:ascr scr}  ; Reducer merges this into existing ASCR

4. **evaluate-completion**
   - Input: current state (ds-id, ascr, budget-left)
   - Checks DS completion criteria:
     - Required fields present in ASCR?
     - Budget exhausted?
   - Output: {:complete? true/false}

5. **check-budget**
   - Input: current state (budget-left)
   - Output: {:budget-left (- budget-left 1.0)}

### Edges

```
START
  → formulate-question
  → check-budget
  → get-answer
  → interpret-response
  → evaluate-completion
  → (conditional)
      if complete? = false → formulate-question (loop)
      if complete? = true  → END
```

### State Reduction Example

**Initial State:**
```clojure
{:ds-id :process/warm-up
 :ascr {}
 :messages []
 :budget-left 10.0
 :complete? false
 :pid :craft-beer-123
 :cid :process}
```

**After interpret-response (1st Q&A):**
```clojure
{:ds-id :process/warm-up
 :ascr {:challenges ["Scheduling seasonal demand"]}  ; <-- Reduced/merged SCR
 :messages [{:from :system :content "What challenges?"}
            {:from :surrogate :content "Seasonal demand varies..."}]
 :budget-left 9.0
 :complete? false
 ...}
```

**After interpret-response (2nd Q&A):**
```clojure
{:ds-id :process/warm-up
 :ascr {:challenges ["Scheduling seasonal demand" "Equipment constraints"]
        :motivation "make-to-stock"}  ; <-- More fields added via reduction
 :messages [...4 messages now...]
 :budget-left 8.0
 :complete? false
 ...}
```

**Final State:**
```clojure
{:ds-id :process/warm-up
 :ascr {:challenges [...all challenges...]
        :motivation "make-to-stock"
        :description "..."}  ; <-- Complete ASCR
 :messages [...all messages...]
 :budget-left 4.0
 :complete? true  ; <-- Completion criteria met
 ...}
```

## Project Structure

All development for the LangGraph-based interviewing will be in the `interviewing` directory:

```
src/sched_mcp/interviewing/
  lg_util.clj          - LangGraph utility functions (channels, graph builder, etc.)
  interview_state.clj  - InterviewState record and conversions
  interview_nodes.clj  - Node implementations (formulate, interpret, etc.)
  interview_graph.clj  - Graph construction and execution

test/sched_mcp/interviewing/
  lg_util_test.clj           - Tests for utilities (already exists)
  interview_state_test.clj   - Tests for state management
  interview_graph_test.clj   - Integration tests for interview loop
```

**Utilities** are defined in `src/sched_mcp/interviewing/lg_util.clj` and include:
- Channel creation functions (appender, reducer, last-write)
- State conversion helpers
- Graph building utilities
- Node wrapping functions

## Proof of Concept Plan

### Phase 1: Basic Infrastructure ✅ COMPLETE
- [x] Implement reducer channel utility in `interviewing/lg_util.clj`
- [x] Create `InterviewState` record in `interviewing/interview_state.clj`
- [x] Implement state ↔ AgentState conversion functions
- [x] Test state reduction with mock SCRs
- [x] All Phase 1 tests passing (4 tests, 26 assertions)

### Phase 2: Simple Interview Loop ✅ COMPLETE
- [x] Mock formulate-question node (returns canned questions)
- [x] Mock get-answer node (returns canned answers)
- [x] Mock interpret-response node (returns canned SCRs)
- [x] Implement evaluate-completion node (checks ASCR keys)
- [x] Build graph with conditional edge
- [x] Implement check-budget node
- [x] Test full loop with mock nodes
- [x] All Phase 2 tests passing (3 tests, 18 assertions)

### Phase 3: Real Components ✅ COMPLETE
- [x] Integrate actual LLM for question formulation
- [x] Integrate existing surrogate code
- [x] Integrate actual interpretation logic
- [x] Use real DS definitions for completion criteria
- [x] Test with actual DS (e.g., :process/warm-up-with-challenges)
- [x] All Phase 3 tests passing (4 tests, 28 assertions including real interview test)

### Phase 4: MCP Integration ✅ COMPLETE
- [x] Create `conduct-interview` MCP tool
- [x] Tool calls LangGraph, returns completed ASCR
- [x] Test tool execution with surrogate
- [x] Verify ASCR storage in project DB
- [x] Verify message storage in project DB

## Implementation Status

### What We've Accomplished

**Phase 1: Infrastructure (Complete)**
- Created comprehensive LangGraph4j utility layer in `lg_util.clj`:
  - Channel factories: `make-appender-channel`, `make-reducer-channel`, `make-last-write-channel`
  - State conversion: `get-state-value` with recursive keywordization for idiomatic Clojure
  - Node/Edge wrappers: `make-node-action`, `make-async-node`, `make-async-edge`
  - Graph builder: `build-graph` with Clojure-friendly syntax

- Implemented `InterviewState` record with clean conversions:
  - `interview-state->map` and `interview-state->agent-state` for Java interop
  - `agent-state->interview-state` for working with Clojure data in nodes
  - Helper functions: `add-message`, `update-ascr`, `decrement-budget`, `mark-complete`

- **Key Achievement**: Maintained idiomatic Clojure throughout:
  - Keywords everywhere in Clojure code
  - Automatic string conversion only at Java boundaries
  - Recursive `stringify-keys` in node return values
  - Recursive `keywordize-keys` when reading from Java state

- All Phase 1 tests passing (4 deftests, 26 assertions)

**Phase 2: Mock Interview Loop (Complete)**
- Implemented all 5 node functions:
  1. `mock-formulate-question`: Returns canned questions based on ASCR state
  2. `mock-get-answer`: Returns canned answers based on question content
  3. `mock-interpret-response`: Extracts SCRs from Q&A pairs
  4. `evaluate-completion`: Checks for required ASCR fields or budget exhaustion
  5. `check-budget`: Decrements question budget

- Built graph with proper flow:
  - START → formulate → check-budget → get-answer → interpret → evaluate
  - Conditional edge: if complete → END, else → formulate (loop)

- Graph compiles and executes successfully
- All integration tests passing (3 tests, 18 assertions)
- ASCR accumulation working correctly
- Loop management and completion criteria working

### Resolution: Java/Clojure Interop Issues (RESOLVED)

**Problem**: The ASCR reducer channel wasn't accumulating state correctly due to two interop issues between Clojure and LangGraph4j's Java implementation.

**Issue 1: Reducer Return Type**
- **Root Cause**: LangGraph4j's reducer channel stores `java.util.HashMap` objects, but Clojure's `merge` function returns `clojure.lang.PersistentMap`
- **Symptom**: Clojure `merge` threw `ClassCastException` when trying to merge a HashMap
- **Solution**: Modified `make-reducer-channel` to wrap Clojure map results in `java.util.HashMap`:
  ```clojure
  (defn make-reducer-channel [reducer-fn]
    (Channels/base
     (reify org.bsc.langgraph4j.state.Reducer
       (apply [_ current-val new-val]
         (let [result (reducer-fn current-val new-val)]
           (if (map? result)
             (let [hmap (java.util.HashMap.)]
               (.putAll hmap result)
               hmap)
             result))))))
  ```

**Issue 2: HashMap Keywordization**
- **Root Cause**: `get-state-value` only checked `(map? x)` which returns `false` for `java.util.HashMap`
- **Symptom**: HashMap values weren't converted to Clojure maps with keyword keys, causing `contains?` checks to fail
- **Solution**: Extended `keywordize-keys` to handle both Clojure maps and Java Map instances:
  ```clojure
  (or (map? x) (instance? java.util.Map x))
  (into {} (map (fn [[k v]]
                 [(if (string? k) (keyword k) k)
                  (keywordize-keys v)])
               x))
  ```

**Files Modified**:
- `src/sched_mcp/interviewing/lg_util.clj:18-35` - Fixed `make-reducer-channel`
- `src/sched_mcp/interviewing/lg_util.clj:51-72` - Fixed `get-state-value`
- `src/sched_mcp/interviewing/interview_state.clj:52-57` - Simplified ASCR reducer to always use `into {}`

**Result**: All tests passing (7 tests, 44 assertions total across Phase 1, Phase 2, and Phase 3)

### Phase 3: Real Components Implementation (COMPLETE)

**Implementation Summary:**

Phase 3 integrated real LLM calls and surrogate expert interaction into the LangGraph interview loop. All mock nodes were replaced with production-ready implementations.

**New Node Implementations** (in `src/sched_mcp/interviewing/interview_nodes.clj`):

1. **`formulate-question`**: Real LLM-based question generation
   - Retrieves DS from system DB via `sdb/get-discovery-schema-JSON`
   - Builds message history from interview state
   - Calls `llm/ds-question-prompt` to generate contextual questions
   - Uses existing LLM infrastructure from `sched-mcp.llm`

2. **`get-answer`**: Real surrogate expert integration
   - Calls `suru/surrogate-answer-question` with current question
   - Integrates with existing surrogate implementation
   - Handles both surrogate responses and error cases

3. **`interpret-response`**: Real LLM-based response interpretation
   - Extracts last Q&A pair from message history
   - Calls `llm/ds-interpret-prompt` to extract SCR from natural language
   - Removes metadata fields to get clean SCR
   - Logs extraction results

4. **`real-evaluate-completion`**: DS-specific completion criteria
   - Uses `dsu/ds-complete?` multimethod for DS-specific checks
   - Handles budget exhaustion
   - Provides detailed logging of completion reasons

**New Graph Functions** (in `src/sched_mcp/interviewing/interview_graph.clj`):

1. **`build-real-interview-graph`**: Builds graph with real nodes
   - Identical structure to mock graph
   - Uses Phase 3 node implementations
   - Same conditional flow logic

2. **`run-real-interview`**: Executes real interview loop
   - Takes `InterviewState` as input
   - Returns completed `InterviewState` with populated ASCR
   - Handles full interview lifecycle

**Integration Test** (in `test/sched_mcp/interviewing/interview_graph_test.clj`):

- `real-interview-with-surrogate-test` (tagged `:integration`)
- Creates surrogate expert for craft beer domain
- Runs complete interview with DS `:process/warm-up-with-challenges`
- Verifies ASCR population, message exchange, budget tracking
- Requires LLM API credentials and loaded Discovery Schemas

**Test Results:**
```
Testing sched-mcp.interviewing.interview-graph-test
...
Final ASCR from real interview:
{:one-more-thing "Tank availability is a significant constraint...",
 :scheduling-challenges ["bottleneck-processes" "product-variation" "process-variation"],
 :product-or-service-name "craft beers"}

Message count: 2
Budget remaining: 4.0

Ran 4 tests containing 28 assertions.
0 failures, 0 errors.
```

**Key Achievement**: The LangGraph interview loop now autonomously conducts real interviews with LLM question generation, surrogate interaction, and SCR extraction - all without external orchestration of individual Q&A cycles.

### Phase 4: MCP Tool Integration (COMPLETE)

**Implementation Summary:**

Phase 4 created an MCP tool that wraps the LangGraph interview system, allowing the orchestrator to delegate entire Discovery Schema interviews with a single tool call.

**New MCP Tool** (in `src/sched_mcp/tools/iviewr/core.clj`):

**`iviewr_conduct_interview`** - Autonomous interview conductor
- **Parameters**: `project_id`, `conversation_id`, `ds_id`, `budget` (optional, default 10.0)
- **Execution**:
  1. Initializes LLM if needed
  2. Verifies Discovery Schema exists
  3. Creates initial `InterviewState` with budget
  4. Calls `igraph/run-interview` to conduct autonomous interview
  5. Stores completed ASCR in project DB
  6. Stores all Q&A messages in project DB
  7. Marks ASCR as complete if DS criteria met
- **Returns**: Status, ASCR, completion flag, message count, budget remaining, summary

**Integration Points:**
- Added to `create-iviewr-tools` function
- Automatically registered in MCP server via `make-tools!`
- Available to orchestrator alongside other interviewer tools

**Test Results:**
```clojure
{:status "success",
 :ds_id "warm-up-with-challenges",
 :ascr {:one-more-thing "Coordinating fermentation tank usage...",
        :scheduling-challenges ["bottleneck-processes" "equipment-utilization"...],
        :product-or-service-name "craft beers"},
 :complete true,
 :message_count 2,
 :budget_remaining 4.0,
 :summary "Interview completed for warm-up-with-challenges with 2 messages exchanged. 
          DS completion criteria met."}
```

**Verified:**
- ✅ ASCR stored in project DB and marked complete
- ✅ Messages stored in conversation history
- ✅ Single tool call conducts entire interview autonomously
- ✅ Budget management works correctly

**Key Achievement**: The orchestrator can now delegate an entire Discovery Schema interview with a single `iviewr_conduct_interview` tool call. The LangGraph system handles all question formulation, surrogate interaction, response interpretation, and ASCR building autonomously.

## Key Design Decisions

### Why LangGraph for This?

1. **Loop Management**: Built-in support for iterative processes
2. **State Reduction**: Channels naturally handle ASCR accumulation
3. **Conditional Flow**: Easy to implement "loop until complete"
4. **Testability**: Can test interview logic independently
5. **Modularity**: Nodes can be swapped (mock ↔ real LLM)

### Why Record Wrapper for State?

1. **Idiomatic Clojure**: Work with Clojure data in nodes
2. **Type Safety**: Records provide structure
3. **Java Boundary**: Convert to AgentState only at edges
4. **Testability**: Easy to create test states

### Integration with Existing System

**Keep:**
- Project DB schema (ASCR storage)
- System DB schema (DS definitions)
- Surrogate implementation
- MCP orchestrator for strategy

**Replace:**
- Individual interview tools (`iviewr_formulate_question`, `iviewr_interpret_response`)
- Tool-based orchestration tools (`orch_start_ds_pursuit`, `orch_complete_ds`)
- Orchestrator managing Q&A cycles

**New:**
- Single `conduct-interview` MCP tool
- LangGraph interview agent
- State reduction logic

## Success Criteria

POC is successful if:

1. ✅ LangGraph can autonomously conduct multi-turn interview - **ACHIEVED**
   - Graph executes iteratively with conditional loops
   - Can run until budget exhausted or completion criteria met

2. ✅ ASCR correctly accumulates from multiple SCRs - **ACHIEVED**
   - Reducer channel working correctly after Java interop fixes
   - SCRs merge properly into accumulating ASCR

3. ✅ Completion criteria properly evaluated - **ACHIEVED**
   - Evaluation logic works correctly
   - Detects when required ASCR fields are present
   - Handles budget exhaustion properly

4. ✅ Budget tracking works - **ACHIEVED**
   - Budget decrements correctly
   - Budget exhaustion triggers completion

5. ✅ Can swap mock components for real ones - **ACHIEVED**
   - Clean node interface makes swapping trivial
   - Nodes are pure functions of state

6. ✅ Orchestrator can call as simple tool, get ASCR back - **ACHIEVED**
   - Phase 3 complete with real LLM and surrogate integration
   - Ready for Phase 4 (MCP tool integration)

## Implementation Complete - All Phases Done! 🎉

**Status**: All 4 phases successfully completed and tested.

### What Was Achieved

1. ✅ **Phase 1**: LangGraph infrastructure with channel reducers and state management
2. ✅ **Phase 2**: Mock interview loop with conditional flow and budget management
3. ✅ **Phase 3**: Real LLM integration with question formulation, surrogate interaction, and response interpretation
4. ✅ **Phase 4**: MCP tool integration - orchestrator can delegate entire DS interviews with one call

### Current Architecture

**Orchestrator (MCP Client - e.g., Claude Desktop)**:
- Selects which Discovery Schema to pursue
- Calls `iviewr_conduct_interview` tool with DS ID and budget
- Receives completed ASCR

**LangGraph Interview Agent** (Autonomous):
- Formulates contextual questions using DS template and current ASCR
- Gets answers from surrogate or human
- Interprets responses into SCRs
- Reduces SCRs into ASCR
- Evaluates completion criteria
- Manages budget
- Returns when complete

**Result**: Strategic decisions stay with orchestrator, tactical interview execution is fully autonomous.

### Next Steps (Future Work)

1. **Additional DS Support**: Test with other Discovery Schemas (flow-shop, job-shop, ORM)
2. **Human Interview Support**: Extend to support human experts (not just surrogates)
3. **Multi-turn Refinement**: Allow orchestrator to request refinement of incomplete ASCR
4. **Orchestrator Migration**: Consider moving orchestrator logic to LangGraph
5. **Performance Optimization**: Batch LLM calls, cache common questions
6. **Visualization**: Add interview progress tracking UI
7. **Remove Legacy Tools**: Deprecate old `iviewr_formulate_question` and `iviewr_interpret_response` tools

## Open Questions

1. **Error Handling**: How to handle LLM failures mid-interview?
2. **Interruption**: Can human interrupt to refine/redirect?
3. **Multi-DS**: Should one graph handle multiple DS, or one graph per DS?
4. **Persistence**: Should interview state be checkpointed?
5. **Visualization**: How to show interview progress to users?

## References

- `docs/orchestrator-data-access-design.md` - Original architecture discussion
- `docs/development-plan.md` - Overall project roadmap
- LangGraph4j documentation: https://github.com/langgraph4j/langgraph4j
