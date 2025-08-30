# MCP Discovery Schema Architecture Design

## Executive Summary

This document outlines the architecture and implementation plan for migrating schedulingTBD's Discovery Schema (DS) system to an MCP-based approach. The current system uses a complex internal protocol with message types like CONVERSATION-HISTORY, PURSUE-EADS, etc. We need to redesign this as MCP tools while preserving the sophisticated orchestration and interviewing capabilities.
The term EADS is deprecated. We use "Discovery Schema" and "DS" instead. I have updated this file to reflect the transition to the new terminology.

## Current Architecture Overview

### Key Components

1. **Orchestrator Agent** (see `examples/schedulingTBD/resources/agents/orchestrator.txt`)
   - Selects which EADS (Example Annotated Data Structure, now called Discovery Schema) to pursue
   - Monitors conversation progress
   - Directs interview flow across four domains: Process, Data, Resources, Optimality

2. **Interviewer Agents** (4 types) (see `examples/schedulingTBD/resources/agents/iviewrs/base-iviewr-instructions.txt`)
   - Process Interviewer
   - Data Interviewer
   - Resources Interviewer
   - Optimality Interviewer

3. **Core Concepts**
   - **EADS**: Template data structures with instructions for completion (now called Discovery Schema)
   - **SCR**: Schema-Conforming Responses filled by interviewers

### Current Protocol Flow

```
1. Orchestrator receives CONVERSATION-HISTORY
2. Orchestrator responds with PURSUE-EADS (selects next schema)
3. System sends EADS-INSTRUCTIONS to Interviewer
4. Interviewer receives SUPPLY-QUESTION (with budget)
5. Interviewer sends QUESTION-TO-ASK
6. System relays to user, gets response
7. Interviewer receives INTERVIEWEES-RESPOND
8. Interviewer sends DATA-STRUCTURE-REFINEMENT
9. Repeat 4-8 until EADS complete
10. Return to step 1
```
## Design Discussion: LLM-Based Tools and Flexible Orchestration with MCP

### New Terminology
 - **Discovery Schema** - (DS) a Clojure EDN map (JSON-like structure) that provides an annotated example of kinds of information sought, instigating the interviewer to ask related questions.
 - **Schema-Conforming Response** - (SCR) a structure that conforms to the given DS and interprets an interviewee response to an interviewer question.
     Note: the SCR is stored in the project DB in the interviewee response object.
 - **Aggregated Schema-Conforming Response** - (ASCR) a structure that conforms to the DS and is updated with information from SCRs after each Q/A pair.
     Note: ASCRs for each DS applied are stored in the project DB. The best example DB of this working-- we don't have many --is examples/schedulingTBD/data/projects/sur-craft-beer-4script.edn.
     Look at the db attribute  :project/summary-dstructs.

### Preliminary Thoughts on LLM-Based Interviewer Tools

The basic functionality of the existing system, as documented in the papers in docs/ and implemented in examples/schedulingTBD should not be lost.
Rather, that functionality is to be re-implemented in a more flexible problem solving architecture based on MCP.
We should be able to borrow design ideas and code from examples/schedulingTBD.
For example, the DS in examples/schedulingTBD/src/server/scheduling_tbd/iviewr/domain/ can be reused.
Likewise, we should be able to borrow design ideas and code from examples/clojure-mcp, an MCP client and server we use in Clojure programming.

### Preliminary Thoughts on MCP tools

MCP tools are pure functions.
In light of the current architecture, we foresee two key tools `interviewer_formulates_question`, and `interviewer_interprets_response`.
These tools provide essential semantic mapping services between natural language and DS.
In so doing, they also map between the expert's viewpoint and the interviewees'.

```clojure
;; Formulate contextually appropriate questions
{:name "interviewer_formulates_question"
 :description "LLM-based tool that formulates the next question"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string}
 :implementation "Calls NIST rchat with DS + ASCR + conversation context"
 :returns {:question :string
           :question_type :string
           :rationale :string        ;; Why this question now
           :expected_info [:string]}} ;; What DS fields might be filled

;; Interpret natural language into structured data
{:name "interviewer_interprets_response"
 :description "LLM-based tool that interprets answer into SCR"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string
              :answer :string
              :question_asked :string}
 :implementation "Calls NIST rchat to extract structured data"
 :returns {:scr :object              ;; Structured response
           :confidence :number       ;; How certain about interpretation
           :ambiguities [:string]    ;; What needs clarification
           :commit_notes :string}}
```

### Orchestration Philosophy: Hybrid Approach

The system should combine structured domain knowledge with flexible LLM reasoning:

1. **Tools Provide Information, Not Prescriptions**
   - Tools should be informative (offering options and context)
   - Not prescriptive (dictating exact next steps)
   - This preserves the flexibility that makes Claude + MCP effective

2. **Domain Knowledge Lives in Discovery Schemas**
   - DS templates encode expert knowledge about manufacturing scheduling
   - Tools expose this knowledge to LLM reasoning
   - Intelligence emerges from the interaction

3. **Orchestration Strategies**
   - **Initial Development Phase**: Claude (or similar) acts as orchestrator using general reasoning
   - **Enhancement Development Phase**: Add orchestration support tools that suggest (not dictate) paths
   - **Future Phase**: Could build dedicated orchestrator LLM, but keep it reasoning-based

4. **The Magic Formula**
   ```
   Domain Expertise (in DS) + General Intelligence (LLM) = Flexible Expert System
   ```

### Why This Works for Non-Programming Domains

The flexibility observed in Claude + clojure-mcp comes from:
- **Pattern Recognition**: "This sounds like a flow shop problem"
- **Strategic Thinking**: "We should understand challenges before details"
- **Adaptive Behavior**: "They seem confused, let me rephrase"
- **Context Integration**: "Earlier they mentioned tank constraints..."

These capabilities transfer perfectly to manufacturing scheduling because the approach separates:
- Domain knowledge (encoded in DS and tools)
- Reasoning capabilities (provided by LLM)

This separation allows the system to be both expert and flexible.

## Proposed MCP Architecture

### Design Principles

1. **Stateless Tools**: MCP tools are stateless, so state management moves to the database
2. **Tool Composition**: Break complex interactions into smaller, composable tools
3. **Natural Flow**: Preserve conversational flow while using tool-based interactions
4. **Flexibility**: Support both human interviewers (Claude) and automated orchestration

### Proposed MCP Tools

#### Orchestrator Tools

```clojure
;; 1. Get next Discovery Schema (was "EADS" now "DS") recommendation
{:name "get_next_ds"
 :description "Analyzes conversation history and recommends next DS to pursue"
 :parameters {:project_id :string
              :conversation_id :string}
 :returns {:ds_id :string
           :rationale :string
           :interviewer_type :string ;; "process", "data", etc.
           :priority :number}}

;; 2. Initialize DS pursuit
{:name "start_ds_pursuit"
 :description "Begins working on a specific DS"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string}
 :returns {:ds_instructions :object
           :current_state :object
           :budget :number}}

;; 3. Get DS library
{:name "list_available_ds"
 :description "Lists all DS templates available for a given interviewer type"
 :parameters {:interviewer_type :string ;; "process", "data", etc.
              :completed_ds [:string] ;; already completed
              :project_context :object} ;; optional context
 :returns {:ds_list [{:id :string
                        :name :string
                        :objective :string
                        :prerequisites [:string]}]}}
```

#### Interviewer Tools

```clojure
;; 1. Get next question
{:name "get_next_question"
 :description "Determines next question based on DS and current ASCR"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string}
 :returns {:question :string
           :question_type :string ;; "text", "table", "choice"
           :table_template :string ;; if table type
           :help_text :string
           :budget_cost :number
           :ds_template :object      ;; The full DS template for reference
           :current_ascr :object}}   ;; Current aggregated SCR

;; 2. Process answer
{:name "process_ds_answer"
 :description "Processes user answer and updates DS data structure. Note that we store the SCR"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string
              :question_id :string
              :answer :string}
 :returns {:scr :object                ;; New Schema-Conforming Response
           :updated_ascr :object       ;; Updated Aggregated SCR
           :commit_notes :string
           :completeness :number       ;; 0.0 to 1.0
           :next_action :string}}      ;; "continue", "complete", "needs_clarification"

;; 3. Get DS status
{:name "get_ds_status"
 :description "Gets current state of DS completion"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string}
 :returns {:data_structure :object
           :completed_fields [:string]
           :remaining_fields [:string]
           :completeness :number
           :budget_used :number
           :budget_remaining :number}}

;; 4. Finalize DS
{:name "finalize_ds"
 :description "Marks DS as complete and stores final data structure"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string
              :final_notes :string}
 :returns {:success :boolean
           :data_structure :object
           :validation_results :object}}
```

#### Utility Tools

```clojure
;; 1. Get conversation history
{:name "get_conversation_history"
 :description "Retrieves formatted conversation history"
 :parameters {:project_id :string
              :conversation_id :string
              :include_minizinc :boolean}
 :returns {:scheduling_challenges [:string]
           :activities [{:pursuing_ds :string
                        :questions_answers [{:question :string
                                           :answer :string}]
                        :summary_ds :object}]
           :minizinc :string
           :minizinc_results :string}}

;; 2. Get aggregated SCR
{:name "get_aggregated_scr"
 :description "Gets all schema-conforming responses collected so far"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_filter [:string]} ;; optional filter
 :returns {:scr_by_ds {:ds_id :object}
           :merged_scr :object
           :conflicts [:object]}}
```

### State Management

Since MCP tools are stateless, we need robust state management:

1. **Database Schema**
   ```clojure
   ;; DS pursuit state
   {:pursuit-id :uuid
    :project-id :string
    :conversation-id :string
    :ds-id :string
    :started-at :instant
    :budget-allocated :number
    :budget-used :number
    :current-state :ds-working  ;; :ds-selecting, :ds-working, :ds-complete
    :data-structure :object       ;; current working DS
    :questions-asked [{:id :string :question :string :answer :string}]
    :completeness :number}

   ;; Aggregated SCR
   {:project-id :string
    :scr-version :number
    :completed-ds {:ds-id {:data-structure :object
                               :completed-at :instant
                               :version :number}}
    :scheduling-challenges [:string]
    :problem-type :keyword}
   ```

2. **State Transitions**
   - Tool calls trigger state updates
   - Each DS pursuit is tracked independently
   - Aggregated SCR updated when DS completes

3. **Key State Management Insights**
   - **Stateless Tool Challenge**: Every MCP tool call must be provided with both the Discovery Schema template and the current ASCR
   - **Pure Function Design**: Each tool becomes essentially a pure function: `(DS, ASCR, new_input) → (action, updated_ASCR)`
   - **State Persistence**: The database must maintain:
     - Individual SCRs (stored with each NL response in conversation history)
     - ASCR (best summary, stored separately in `:summary-dstructs` as seen in `examples/schedulingTBD/data/projects/sur-craft-beer-4script.edn`)
   - **Efficient State Passing**: Tools need strategies for:
     - Fetching relevant DS templates
     - Retrieving current ASCR state
     - Merging new SCRs into existing ASCR
     - Handling conflicts when SCRs provide different values

### Integration Strategy

#### Phase 1: Basic Interview Flow (Current Implementation)
- Simple question/answer tools
- Basic state tracking
- Single DS support (warm-up)

#### Phase 2: Discovery Schema Implementation
- [ ] Implement DS library loading from JSON files
- [ ] Create orchestrator recommendation engine
- [ ] Add DS pursuit state management
- [ ] Implement data structure refinement logic
- [ ] Add validation for DS completion

#### Phase 3: Full Orchestration
- [ ] Multi-interviewer support
- [ ] Budget management
- [ ] DS prerequisite checking
- [ ] Conflict resolution for overlapping schemas
- [ ] MiniZinc generation hooks

#### Phase 4: Advanced Features
- [ ] Backchannel communication support
- [ ] Dynamic DS creation
- [ ] Machine learning for orchestration decisions
- [ ] Integration with schedulingTBD visualization

### Usage Patterns

#### Pattern 1: AI-Driven Interview (Claude as Orchestrator and Interviewer)

```
User: "I need help scheduling my brewery"

Claude: [calls start_interview]
        [calls get_next_ds]
        → recommends "process/warm-up-with-challenges"
        [calls start_ds_pursuit]
        [calls get_next_question]
        "What products do you make and what are your scheduling challenges?"

User: "We make craft beer..."

Claude: [calls process_ds_answer]
        [calls get_next_question]
        "Do your products follow the same production process?"

[Continue until DS complete]

Claude: [calls finalize_ds]
        [calls get_next_ds]
        → recommends "process/flow-shop"
        [Process continues...]
```

#### Pattern 2: Guided Interview (Human orchestrator, Claude as interviewer)

```
Human: "Start the flow shop DS"

Claude: [calls start_ds_pursuit with "process/flow-shop"]
        [Conducts interview...]
```

#### Pattern 3: Automated Orchestration

```
System: [Periodically calls get_next_ds]
        [Triggers Claude to conduct specific DS interviews]
        [Aggregates results automatically]
```

### Technical Considerations

1. **Tool Response Sizes**
   - Discovery Schema (DS) instructions can be large
   - May need pagination or streaming for large schemas
   - Consider caching frequently used Discovery Schema (EADS)

2. **Validation**
   - Discovery Schema (EADS) completion validation in Clojure
   - Schema validation for data structures
   - Type checking for refined values

3. **Extensibility**
   - Plugin architecture for custom Discovery Schema (EADS)
   - Domain-specific validators
   - Custom question generators

### Migration Path

#### Pre-Week 1-2 Preparation Tasks

1. **Fix Current Bugs** ✅ (30-60 mins)
   - Fix the `get_interview_answers` null pointer error ✅
   - Fix the issue where `submit_answer` isn't progressing to the next question properly ✅
   - These bugs will block testing of the new DS system
   - See `docs/bug-fixes-2024-01-task1.md` for details

2. **Create DS/EADS File Structure** ✅ (20 mins)
   ```bash
   # Create directories for Discovery Schemas
   mkdir -p resources/discovery-schemas/process
   mkdir -p resources/discovery-schemas/data
   mkdir -p resources/discovery-schemas/resources
   mkdir -p resources/discovery-schemas/optimality

   # Copy over the existing EADS JSON files from schedulingTBD
   cp examples/schedulingTBD/resources/agents/iviewrs/EADS/process/*.json \
      resources/discovery-schemas/process/
   ```

3. **Document Current State** ✅ (30 mins)
   - Create a debugging guide for the current interview system
   - Document what's working and what's not in `docs/current-state.md`

4. **Create a Simple DS Loader Spike** ✅ (45 mins)
   - Proof of concept for loading DS from JSON files
   - Functions: `load-ds-from-file`, `list-available-ds`, `get-ds-by-id`
   - Test with existing warm-up-with-challenges.json
   - See `src/sched_mcp/ds_loader.clj`

5. **Review Existing DS/EADS Examples** ✅ (20 mins)
   - Look at 2-3 EADS JSON files in `examples/schedulingTBD/resources/agents/iviewrs/EADS/`
   - Make notes about structure and patterns
   - See `docs/ds-review-notes.md`

6. **Set Up Development Workflow** ✅ (15 mins)
   - Add helper functions for testing (reset-interview-db!, quick-test-interview)
   - Create shortcuts for common development tasks
   - See `src/sched_mcp/dev_helpers.clj`

7. **Create a Test Checklist** ✅ (10 mins)
   - Document what needs to work before DS implementation
   - Basic interview flow, state persistence, database queries, MCP responses
   - See `docs/test-checklist.md`

### Testing Strategy with Surrogate Experts

The system includes a comprehensive testing approach using "surrogate experts" - AI agents that simulate domain experts for various manufacturing domains:

1. **Surrogate Expert Implementation** (see `examples/schedulingTBD/src/server/scheduling_tbd/surrogate.clj`)
   - AI agents configured to act as domain experts in specific industries
   - Respond to interview questions as if managing a company in that domain
   - Generate realistic responses about production processes, scheduling challenges, and constraints

2. **How It's Made Integration** (see `examples/schedulingTBD/src/server/scheduling_tbd/how_made.clj`)
   - Database of ~1600 segments from Science Channel's "How It's Made" show
   - Each segment represents a different manufacturing process
   - Provides diverse test cases across many industries

3. **Proposed MCP Testing Tools**
   ```clojure
   ;; Create a surrogate expert for testing
   {:name "create_surrogate_expert"
    :description "Creates an AI surrogate that acts as a domain expert for testing"
    :parameters {:product :string        ;; e.g., "craft beer", "ball bearings"
                 :expertise_level :string ;; "basic", "intermediate", "expert"
                 :company_size :string}   ;; "small", "medium", "large"
    :returns {:surrogate_id :string
              :system_prompt :string
              :domain_knowledge :object}}

   ;; Run automated interview with surrogate
   {:name "run_surrogate_interview"
    :description "Conducts an automated interview with a surrogate expert"
    :parameters {:surrogate_id :string
                 :ds_sequence [:string]   ;; List of DS IDs to pursue
                 :max_questions :number}
    :returns {:completed_ds [:string]
              :ascr :object
              :transcript :object
              :success_metrics :object}}
   ```

4. **Testing Scenarios**
   - **Unit Testing**: Individual DS completion with predefined responses
   - **Integration Testing**: Full interview flows with surrogate experts
   - **Stress Testing**: Running interviews for all 1600 "How It's Made" segments
   - **Validation Testing**: Comparing generated MiniZinc models against known solutions

5. **Benefits of Surrogate Testing**
   - Rapid iteration without human involvement
   - Consistent, repeatable test scenarios
   - Coverage of diverse manufacturing domains
   - Ability to test edge cases and error handling

### Success Criteria

1. **Functional Requirements**
   - [ ] Can load and execute all existing Discovery Schema (EADS)
   - [ ] Maintains conversation continuity
   - [ ] Generates valid SCRs
   - [ ] Supports orchestration decisions

2. **Non-Functional Requirements**
   - [ ] Tool calls complete in <2 seconds
   - [ ] State persists across sessions
   - [ ] Clear error messages
   - [ ] Extensible architecture

### Open Questions

1. **Orchestration Complexity**: Should orchestration logic be in tools or in Claude's reasoning?
2. **State Granularity**: How much state in DB vs. returned in tool responses?
3. **Discovery Schema (EADS) Evolution**: How to handle Discovery Schema (EADS) version changes?
4. **Multi-user**: Support for collaborative interviews?

### Discussion Questions

1. **Agent Configuration in MCP**: Currently, there are two agent descriptions in `~/.claude/agents` that are relevant to this project:
   - These agents are likely interviewer for process and data.
   - It's unclear how to properly integrate these into the MCP project structure
   - Questions to resolve:
     - Should these be part of the MCP server configuration?
     - How do we reference them from MCP tools?
     - Should they be versioned with the project?
     - Do they belong in a project-specific location rather than the global `~/.claude/agents`?
   - **Action needed**: Investigate MCP's approach to agent configuration and determine the best practice for project-specific agents

   - Note: I (Peter) added these files to a new directory, docs/agents, for the time being.


### Conclusion

This architecture preserves the sophisticated Discovery Schema system while adapting it to MCP's tool-based paradigm.
The key innovation is decomposing the complex protocol into discrete, composable tools that can be orchestrated by either AI agents or automated systems.
This provides flexibility while maintaining the domain expertise encoded in the Discovery Schema (EADS) templates.
