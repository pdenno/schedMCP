# MCP Discovery Schema Architecture Design

## Executive Summary

This document outlines the architecture and implementation plan for migrating schedulingTBD's Discovery Schema (DS) system to an MCP-based approach. The current system uses a complex internal protocol with message types like CONVERSATION-HISTORY, PURSUE-EADS, etc. We need to redesign this as MCP tools while preserving the sophisticated orchestration and interviewing capabilities.

## Current Architecture Overview

### Key Components

1. **Orchestrator Agent**
   - Selects which EADS (Expert-defined Argument Discovery Schema) to pursue
   - Monitors conversation progress
   - Directs interview flow across four domains: Process, Data, Resources, Optimality

2. **Interviewer Agents** (4 types)
   - Process Interviewer
   - Data Interviewer
   - Resources Interviewer
   - Optimality Interviewer

3. **Core Concepts**
   - **EADS**: Template data structures with instructions for completion
   - **SCR**: Schema-Conforming Responses filled by interviewers
   - **DSR**: Data Structure Refinement messages containing completed schemas

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

## Proposed MCP Architecture

### Design Principles

1. **Stateless Tools**: MCP tools are stateless, so state management moves to the database
2. **Tool Composition**: Break complex interactions into smaller, composable tools
3. **Natural Flow**: Preserve conversational flow while using tool-based interactions
4. **Flexibility**: Support both human interviewers (Claude) and automated orchestration

### Proposed MCP Tools

#### Orchestrator Tools

```clojure
;; 1. Get next EADS recommendation
{:name "get_next_eads"
 :description "Analyzes conversation history and recommends next EADS to pursue"
 :parameters {:project_id :string
              :conversation_id :string}
 :returns {:eads_id :string
           :rationale :string
           :interviewer_type :string ;; "process", "data", etc.
           :priority :number}}

;; 2. Initialize EADS pursuit
{:name "start_eads_pursuit"
 :description "Begins working on a specific EADS"
 :parameters {:project_id :string
              :conversation_id :string
              :eads_id :string}
 :returns {:eads_instructions :object
           :current_state :object
           :budget :number}}

;; 3. Get EADS library
{:name "list_available_eads"
 :description "Lists all EADS templates available for a given interviewer type"
 :parameters {:interviewer_type :string ;; "process", "data", etc.
              :completed_eads [:string] ;; already completed
              :project_context :object} ;; optional context
 :returns {:eads_list [{:id :string
                        :name :string
                        :objective :string
                        :prerequisites [:string]}]}}
```

#### Interviewer Tools

```clojure
;; 1. Get next question
{:name "get_next_question"
 :description "Determines next question based on EADS and responses so far"
 :parameters {:project_id :string
              :conversation_id :string
              :eads_id :string
              :responses_so_far :object}
 :returns {:question :string
           :question_type :string ;; "text", "table", "choice"
           :table_template :string ;; if table type
           :help_text :string
           :budget_cost :number}}

;; 2. Process answer
{:name "process_eads_answer"
 :description "Processes user answer and updates EADS data structure"
 :parameters {:project_id :string
              :conversation_id :string
              :eads_id :string
              :question_id :string
              :answer :string}
 :returns {:data_structure_update :object
           :commit_notes :string
           :completeness :number ;; 0.0 to 1.0
           :next_action :string}} ;; "continue", "complete", "needs_clarification"

;; 3. Get EADS status
{:name "get_eads_status"
 :description "Gets current state of EADS completion"
 :parameters {:project_id :string
              :conversation_id :string
              :eads_id :string}
 :returns {:data_structure :object
           :completed_fields [:string]
           :remaining_fields [:string]
           :completeness :number
           :budget_used :number
           :budget_remaining :number}}

;; 4. Finalize EADS
{:name "finalize_eads"
 :description "Marks EADS as complete and stores final data structure"
 :parameters {:project_id :string
              :conversation_id :string
              :eads_id :string
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
           :activities [{:pursuing_eads :string
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
              :eads_filter [:string]} ;; optional filter
 :returns {:scr_by_eads {:eads_id :object}
           :merged_scr :object
           :conflicts [:object]}}
```

### State Management

Since MCP tools are stateless, we need robust state management:

1. **Database Schema**
   ```clojure
   ;; EADS pursuit state
   {:pursuit-id :uuid
    :project-id :string
    :conversation-id :string
    :eads-id :string
    :started-at :instant
    :budget-allocated :number
    :budget-used :number
    :current-state :eads-working  ;; :eads-selecting, :eads-working, :eads-complete
    :data-structure :object       ;; current working DS
    :questions-asked [{:id :string :question :string :answer :string}]
    :completeness :number}

   ;; Aggregated SCR
   {:project-id :string
    :scr-version :number
    :completed-eads {:eads-id {:data-structure :object
                               :completed-at :instant
                               :version :number}}
    :scheduling-challenges [:string]
    :problem-type :keyword}
   ```

2. **State Transitions**
   - Tool calls trigger state updates
   - Each EADS pursuit is tracked independently
   - Aggregated SCR updated when EADS completes

### Integration Strategy

#### Phase 1: Basic Interview Flow (Current Implementation)
- Simple question/answer tools
- Basic state tracking
- Single EADS support (warm-up)

#### Phase 2: Discovery Schema Implementation
- [ ] Implement EADS library loading from JSON files
- [ ] Create orchestrator recommendation engine
- [ ] Add EADS pursuit state management
- [ ] Implement data structure refinement logic
- [ ] Add validation for EADS completion

#### Phase 3: Full Orchestration
- [ ] Multi-interviewer support
- [ ] Budget management
- [ ] EADS prerequisite checking
- [ ] Conflict resolution for overlapping schemas
- [ ] MiniZinc generation hooks

#### Phase 4: Advanced Features
- [ ] Backchannel communication support
- [ ] Dynamic EADS creation
- [ ] Machine learning for orchestration decisions
- [ ] Integration with schedulingTBD visualization

### Usage Patterns

#### Pattern 1: AI-Driven Interview (Claude as Orchestrator and Interviewer)

```
User: "I need help scheduling my brewery"

Claude: [calls start_interview]
        [calls get_next_eads]
        → recommends "process/warm-up-with-challenges"
        [calls start_eads_pursuit]
        [calls get_next_question]
        "What products do you make and what are your scheduling challenges?"

User: "We make craft beer..."

Claude: [calls process_eads_answer]
        [calls get_next_question]
        "Do your products follow the same production process?"

[Continue until EADS complete]

Claude: [calls finalize_eads]
        [calls get_next_eads]
        → recommends "process/flow-shop"
        [Process continues...]
```

#### Pattern 2: Guided Interview (Human orchestrator, Claude as interviewer)

```
Human: "Start the flow shop EADS"

Claude: [calls start_eads_pursuit with "process/flow-shop"]
        [Conducts interview...]
```

#### Pattern 3: Automated Orchestration

```
System: [Periodically calls get_next_eads]
        [Triggers Claude to conduct specific EADS interviews]
        [Aggregates results automatically]
```

### Technical Considerations

1. **Tool Response Sizes**
   - EADS instructions can be large
   - May need pagination or streaming for large schemas
   - Consider caching frequently used EADS

2. **Validation**
   - EADS completion validation in Clojure
   - Schema validation for data structures
   - Type checking for refined values

3. **Extensibility**
   - Plugin architecture for custom EADS
   - Domain-specific validators
   - Custom question generators

### Migration Path

1. **Week 1-2**: Implement core EADS tools
   - Load EADS from JSON
   - Basic pursuit tracking
   - Question generation

2. **Week 3-4**: Orchestration logic
   - Recommendation engine
   - State management
   - Budget tracking

3. **Week 5-6**: Integration
   - Connect with existing interview system
   - Test with real EADS
   - Refine tool interfaces

4. **Week 7-8**: Polish and extend
   - Add remaining interviewers
   - Implement validation
   - Performance optimization

### Success Criteria

1. **Functional Requirements**
   - [ ] Can load and execute all existing EADS
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
3. **EADS Evolution**: How to handle EADS version changes?
4. **Multi-user**: Support for collaborative interviews?

### Conclusion

This architecture preserves the sophisticated Discovery Schema system while adapting it to MCP's tool-based paradigm. The key innovation is decomposing the complex protocol into discrete, composable tools that can be orchestrated by either AI agents or automated systems. This provides flexibility while maintaining the domain expertise encoded in the EADS templates.
