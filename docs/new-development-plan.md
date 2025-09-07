# schedMCP Development Plan - Phase 3 and Beyond

## Executive Summary

This document outlines the development plan for schedMCP, focusing on Phase 2.5 Discovery Schema flow and then Phase 3 table-based communication and integration opportunities with LangGraph and MCP-UI.

**Important Update**: Before implementing advanced features, we need to complete Phase 2.5 - ensuring the core Discovery Schema flow works properly with SCR storage and ASCR aggregation.
This is a prerequisite for all subsequent phases.

The project has successfully implemented basic interview functionality with LLM-powered question generation and response interpretation.
The next phase will enhance the system with structured data collection capabilities while maintaining the flexibility of natural language interaction.

## System Architecture Overview

### Core Concepts

1. **Discovery Schema (DS)** - JSON/EDN structures that define the information to be collected during interviews. These replace the deprecated term "EADS" (Example Annotated Data Structures).

2. **Schema-Conforming Response (SCR)** - Structured data extracted from individual user responses that conforms to the DS template.

3. **Aggregated Schema-Conforming Response (ASCR)** - Combined SCRs that represent the complete state of information collected for a DS.

4. **Orchestration** - The process of selecting and sequencing DS interviews based on collected information and domain logic.

### Architectural Principles

1. **Stateless Tools**: MCP tools are pure functions, with all state managed in the database
2. **Tool Composition**: Complex interactions decomposed into smaller, composable tools
3. **Natural Flow**: Conversational interface preserved while collecting structured data
4. **Flexibility**: Support for both AI-driven and human-guided orchestration

### The Magic Formula
```
Domain Expertise (in DS) + General Intelligence (LLM) = Flexible Expert System
```

## Current Implementation Status

### Completed Features

1. **Basic Interview Flow (Phase 1)**
   - Warm-up phase with scheduling challenges questions
   - Question/answer mechanics via MCP tools
   - State persistence using Datahike

2. **Discovery Schema Infrastructure (Phase 2)**
   - 8 DS templates loaded from JSON:
     - Process: warm-up, flow-shop, job-shop (3 variants), timetabling
     - Data: ORM (Object-Role Modeling)
   - DS loader with validation
   - ASCR (Aggregated Schema-Conforming Response) management

3. **LLM Integration**
   - `iviewr_formulate_question`: Generates contextual questions from DS + ASCR
   - `iviewr_interpret_response`: Extracts structured data from natural language
   - Integration with OpenAI GPT-4

4. **Surrogate Expert System**
   - LLM-based domain expert simulation
   - Supports multiple manufacturing domains
   - Maintains conversation consistency
   - Integration with "How It's Made" database (~1600 manufacturing processes)

5. **Orchestration Foundation**
   - Flow graph defining DS progression paths
   - Priority-based DS selection
   - Multi-phase interview support

### Existing Assets

**Important Note**: We have an existing React table implementation in `examples/schedulingTBD/src/app/stbd_app/components/table2.cljs` that uses MUI DataGrid. This component already handles:
- Table rendering with editable cells
- Row addition/removal
- Row reordering
- Data transformation between internal format and display format

This existing implementation can serve as a reference or potentially be adapted for the MCP-UI integration.

## Recent Architectural Decisions (September 2025)

Based on our discussion, we've made several important decisions:

### 1. Tool Naming with Agent Prefixes ✓ COMPLETED
Tools now have clear agent prefixes to indicate ownership:
- **Interviewer tools** (`iviewr_`): formulate_question, interpret_response, get_current_ds
- **Orchestrator tools** (`orch_`): get_next_ds, start_ds_pursuit, complete_ds, get_progress
- **Surrogate tools** (`sur_`): start_expert, answer, get_session
- **Shared tools** (no prefix): start_interview, get_interview_context, get_interview_answers, submit_answer

This naming convention immediately tells you which "persona" should be using each tool.

### 2. Single Surrogate Session ✓ COMPLETED
We simplified from multiple concurrent surrogate sessions to a single active session:
- Removed `list_surrogate_sessions` tool
- Changed `expert-sessions-atom` to `current-expert-session`
- Dramatically simplified the mental model

### 3. "Phase 2.5" Tasks
We discovered that Phase 2.5 (SCR → ASCR flow) is already implemented:
- `iviewr_interpret_response` stores SCRs with messages
- `combine-ds!` aggregates SCRs into ASCRs after each response
- Renamed `:project/summary-DS` to `:project/ASCRs` for clarity
- We **have not** yet demonstrated that we can create start a surrogate project, run the interview and produce ASCR in the project's DB. **THIS IS WHAT WE'LL WORK ON NOW.**

### 4. LangGraph4j for Orchestration (Future)
We will use LangGraph4j (Java implementation) for orchestration, with ASCR as reducer state:
- Natural fit for the ASCR accumulation pattern
- Provides dynamic flow control based on responses
- Enables backtracking and state checkpointing

### 5. Agent Instructions (To Be Implemented)
Currently `orch_get_next_ds` has hard-coded logic. We need proper agent instructions like schedulingTBD:
- Each agent should have clear role descriptions
- Decision-making guidelines
- Domain knowledge encoded in instructions
- LLM-powered deliberation instead of rigid rules

### 6. Growing Beyond Claude as Orchestrator
Current phase: Claude Desktop calls MCP tools directly
Future phases will have dedicated orchestration with multiple specialized modules:
- Interview Module (LangGraph)
- DSL Mentor Module
- MiniZinc Runner Module
- Quiz/Validation Module
- MCP-UI for tables/visualization

## User Experience Flow: Claude Desktop Integration

### The Missing Link: How Users Actually Use This

Currently, we have MCP tools but no clear path for users to interact with Claude Desktop for scheduling interviews. Here's how it should work:

#### 1. User Setup (One-time)
```bash
# User installs schedMCP server
git clone <schedMCP-repo>
cd schedMCP

# User configures Claude Desktop (claude_desktop_config.json)
{
  "mcpServers": {
    "schedmcp": {
      "command": "clojure",
      "args": ["-M", "-m", "sched-mcp.main"],
      "cwd": "/path/to/schedMCP",
      "env": {
        "SCHEDULING_TBD_DB": "/opt/scheduling"
      }
    }
  }
}
```

#### 2. Starting an Interview Session

**User opens Claude Desktop and says:**
"I need help creating a production schedule for my brewery"

**Claude (using MCP tools):**
```
[calls start_interview with project_name="User's Brewery"]
[calls get_next_ds] → returns "warm-up-with-challenges"
[calls start_ds_pursuit]
[calls formulate_question]
```

**Claude responds:**
"I'll help you create a production schedule for your brewery. Let me start by understanding your current scheduling challenges.

What are the main scheduling difficulties you face in your brewery operations?"

#### 3. Natural Conversation Flow

The key insight is that **Claude acts as both the orchestrator AND the interviewer**, using the MCP tools transparently:

**User:** "We have 5 fermentation tanks and struggle to coordinate..."

**Claude:**
```
[calls interpret_response] → extracts SCR
[calls formulate_question] → generates next question
```
"I understand you have 5 fermentation tanks. What types of beer do you produce, and do they have different fermentation times?"

#### 4. Table-Based Questions

**Claude:**
```
[calls formulate_table_question]
```
"Could you fill in this table with your tank capacities and current usage?"

[Presents HTML table in the conversation]

**Critical Gap:** How does Claude Desktop render and accept edited HTML tables? Options:
1. Claude could provide a copy-paste HTML form
2. Integration with MCP-UI for inline editing
3. Claude could guide users to fill tables conversationally

### Implementation Requirements for User Flow

1. **Claude Prompting Enhancement**
   - Add system instructions to Claude about available MCP tools
   - Train Claude on when to use which tools
   - Provide examples of natural conversation patterns

2. **Tool Response Formatting**
   - Tools should return user-friendly text, not just data
   - Include suggested phrasing for Claude to use
   - Handle error cases gracefully

3. **Session Management**
   - Clear commands to start/resume/end interviews
   - Ability to list active projects
   - Simple project switching

### Example Complete Interaction

```
User: "I need help scheduling my craft brewery"

Claude: "I'll help you create a production schedule. Let me understand your
operation first. What products do you make and what are your main
scheduling challenges?"
[behind scenes: start_interview → get_next_ds → formulate_question]

User: "We make 5 types of beer. Our main issue is tank scheduling."

Claude: "I see. Do all 5 beer types follow the same general production
process, or are there significant differences?"
[behind scenes: interpret_response → formulate_question]

User: "They're similar but have different fermentation times"

Claude: "That's a common challenge. Let me gather some specific information
about your resources. Could you fill in this table with your tanks?"

┌─────────────┬──────────┬─────────────┬───────────────┐
│ Tank Name   │ Capacity │ Current Use │ Available Date│
├─────────────┼──────────┼─────────────┼───────────────┤
│ Tank-1      │ 500L     │ IPA         │ Dec 15        │
│ Tank-2      │ 500L     │ [empty]     │ [empty]       │
│ [add more]  │          │             │               │
└─────────────┴──────────┴─────────────┴───────────────┘

[User edits table in Claude Desktop somehow - THIS IS THE KEY CHALLENGE]

Claude: "Perfect! Now let's map out your brewing process..."
[behind scenes: process_table_response → get_next_ds → ...]
```

## Phase 2.5: Core Discovery Schema Flow ✓ COMPLETED

### Overview

The core Discovery Schema flow is now fully implemented. SCR (Schema-Conforming Response) storage and ASCR (Aggregated SCR) management work correctly for text-based Q&A.

### What Was Implemented

1. **SCR Storage**: The `iviewr_interpret_response` tool extracts SCRs and stores them in the database with each message
2. **ASCR Updates**: The aggregation logic (`combine-ds!`) is triggered after each Q&A
3. **DS Completion**: Clear flow from completing one DS to starting the next via `ds-complete?`
4. **Schema Updates**: Renamed `:project/summary-DS` to `:project/ASCRs` for clarity

### How It Works

```clojure
;; In iviewr_interpret_response:
(d/transact conn [{:message/id message-id
                   :message/scr (pr-str scr)  ; SCR stored here
                   :message/timestamp (java.util.Date.)
                   ;; ... other fields
                   }])

;; Then immediately:
(let [updated-ascr (combine/combine-ds! ds-kw project-kw)  ; Aggregates all SCRs
      complete? (combine/ds-complete? ds-kw project-kw)]    ; Checks completion
  ;; Return comprehensive result with ASCR
  {:scr scr
   :updated_ascr updated-ascr
   :ds_complete complete?
   :completeness (calculate-percentage)})
```

### Success Criteria Met

1. **SCR Storage Works** ✓
   - SCRs are queryable from database via `:message/scr`
   - Each SCR is linked to its pursuit

2. **ASCR Aggregation Works** ✓
   - ASCRs contain merged data from all SCRs
   - Custom merge logic for different DS types
   - Versioned storage in database

3. **DS Completion Works** ✓
   - Returns true when all required fields filled
   - Triggers pursuit status update

4. **Full Conversation Flow** ✓
   - Can complete warm-up DS through conversation
   - Can transition to next DS
   - All DSs show proper ASCRs in database

## Phase 3: Table-Based Communication

### Overview

Phase 3 focuses on enabling interviewees to fill in tables created by interviewers, providing a more efficient way to collect structured data while maintaining the conversational flow.
**This phase depends on Phase 2.5 being complete.**

### Implementation Plan

#### 1. Table Communication Infrastructure (Week 1)

**New namespace: `sched-mcp.table-comm`**

```clojure
(ns sched-mcp.table-comm
  "Table generation and parsing for structured data collection"
  (:require
   [clojure.string :as str]
   [hickory.core :as hickory]
   [hickory.select :as s]))

(defn ds-table-spec->html
  "Convert DS table specification to HTML table"
  [table-spec prefilled-data]
  ;; Generate HTML with:
  ;; - Proper column headers from DS
  ;; - Pre-filled data where available
  ;; - Empty cells for user input
  ;; - Data attributes for validation
  )

(defn parse-html-table
  "Extract structured data from user-edited HTML table"
  [html-content table-spec]
  ;; Parse HTML and return:
  ;; - Extracted cell values
  ;; - Validation results
  ;; - SCR-compatible data structure
  )

(defn validate-table-data
  "Validate parsed table against DS constraints"
  [table-data table-spec]
  ;; Check:
  ;; - Required columns filled
  ;; - Data type constraints
  ;; - Cross-references with existing ASCR
  )
```

**Integration with existing tools:**

```clojure
;; New tool for table-based questions
(defmethod tool-system/execute-tool :formulate-table-question
  [{:keys [system-atom]} {:keys [project-id conversation-id ds-id]}]
  (let [ds-template (ds/load-ds ds-id)
        current-ascr (get-current-ascr project-id ds-id)
        table-spec (get-in ds-template [:tables 0])]
    (when table-spec
      {:question (generate-table-prompt table-spec)
       :table_html (ds-table-spec->html table-spec current-ascr)
       :question_type "table"
       :help_text "Please fill in the table below"})))
```

#### 2. DS Enhancement for Tables (Week 1)

**Update DS JSON format to include table specifications:**

```json
{
  "ds-id": "process/resource-matrix",
  "description": "Capture resource capabilities and constraints",
  "tables": [{
    "id": "resource-capabilities",
    "prompt": "Please list your resources and their capabilities",
    "columns": [
      {
        "id": "resource_name",
        "label": "Resource Name",
        "type": "string",
        "required": true,
        "example": "CNC-1"
      },
      {
        "id": "capability",
        "label": "Capability/Function",
        "type": "string",
        "required": true,
        "example": "milling, drilling"
      },
      {
        "id": "capacity",
        "label": "Capacity",
        "type": "number",
        "unit": "units/hour",
        "required": false
      }
    ],
    "example_rows": [
      {
        "resource_name": "CNC-1",
        "capability": "milling",
        "capacity": 10
      }
    ],
    "validation_rules": {
      "min_rows": 1,
      "max_rows": 50,
      "unique_columns": ["resource_name"]
    }
  }]
}
```

#### 3. MCP Tool Updates (Week 1-2)

**New tools for table handling:**

```clojure
{:name "present_table_for_editing"
 :description "Present an HTML table for user to fill in"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string
              :table_id :string}
 :returns {:table_html :string
           :instructions :string
           :validation_rules :object}}

{:name "process_table_response"
 :description "Process user-edited table and update ASCR"
 :parameters {:project_id :string
              :conversation_id :string
              :ds_id :string
              :table_id :string
              :edited_html :string}
 :returns {:scr :object
           :validation_errors [:string]
           :updated_ascr :object}}
```

### MCP-UI Integration Strategy

#### 1. Research Phase (Week 2)

- Study mcp-ui documentation at https://mcpui.dev/
- Analyze how our existing React table component could be adapted
- Design integration architecture

#### 2. Proof of Concept (Week 2)

```javascript
// Conceptual MCP-UI component
const TableEditor = ({ tableSpec, initialData, onSubmit }) => {
  // Could potentially reuse logic from table2.cljs
  // Convert between MCP format and DataGrid format
  // Handle validation on client side
  return (
    <DataGrid
      rows={initialData}
      columns={tableSpec.columns}
      onRowUpdate={handleUpdate}
      // ... other props
    />
  );
};
```

#### 3. Integration Architecture (Week 2-3)

- MCP server provides table specifications
- UI renders editable tables client-side
- Validation happens both client and server side
- Results returned through standard MCP channels

## LangGraph Integration Exploration

### Rationale

LangGraph can enhance the orchestration layer by providing:
- Dynamic flow control based on interview responses
- Parallel execution of independent DS
- Backtracking when contradictions are discovered
- State checkpointing for complex interviews

### Proposed Architecture

- Though we are showing python here, we'll probably use the Java library langgraph4j. We need to discuss this.

```python
from langgraph import StateGraph, END
from typing import TypedDict, List, Dict

class InterviewState(TypedDict):
    """State maintained throughout the interview"""
    project_id: str
    current_ds: str
    completed_ds: List[str]
    ascr: Dict
    confidence_scores: Dict
    discovered_constraints: List[str]

def create_interview_graph():
    """Create the LangGraph workflow for interviews"""
    graph = StateGraph(InterviewState)

    # Add nodes for each phase
    graph.add_node("warm_up", warm_up_interview)
    graph.add_node("determine_process_type", analyze_process_type)
    graph.add_node("flow_shop_details", flow_shop_interview)
    graph.add_node("job_shop_details", job_shop_interview)
    graph.add_node("resource_mapping", resource_interview)
    graph.add_node("constraints", constraint_interview)
    graph.add_node("optimization", optimization_interview)

    # Add conditional routing
    graph.add_conditional_edges(
        "determine_process_type",
        route_by_process_type,
        {
            "flow": "flow_shop_details",
            "job": "job_shop_details",
            "hybrid": "hybrid_process_details"
        }
    )

    # Add parallel execution for independent phases
    graph.add_edge("flow_shop_details", "resource_mapping")
    graph.add_edge("job_shop_details", "resource_mapping")

    # Compile and return
    return graph.compile()
```

### Integration Points

1. **MCP Tools as Graph Nodes**
   - Each node calls appropriate MCP tools
   - State passed between nodes via LangGraph

2. **Dynamic Routing**
   - Based on ASCR content and confidence scores
   - Can return to earlier nodes if needed

3. **Checkpoint Management**
   - Save state at each node completion
   - Resume from any point in the interview

## State Management Architecture

Since MCP tools are stateless, robust state management is critical:

### Database Schema
```clojure
;; DS pursuit state
{:pursuit/id :uuid
 :pursuit/project-id :string
 :pursuit/conversation-id :string
 :pursuit/ds-id :string
 :pursuit/started-at :instant
 :pursuit/budget-allocated :number
 :pursuit/budget-used :number
 :pursuit/state :keyword  ;; :selecting, :working, :complete
 :pursuit/ascr :object    ;; current aggregated responses
 :pursuit/questions [{:id :string :question :string :answer :string :scr :object}]}

;; Project state
{:project/id :string
 :project/completed-ds [:string]
 :project/summary-dstructs {:ds-id :object}  ;; Final ASCRs
 :project/scheduling-challenges [:string]
 :project/problem-type :keyword}
```

### Key State Management Insights

1. **Stateless Tool Challenge**: Every tool call receives DS template + current ASCR
2. **Pure Function Design**: Tools are `(DS, ASCR, input) → (action, updated_ASCR)`
3. **State Persistence**:
   - Individual SCRs stored with each response
   - ASCR maintained separately as "best summary"
   - See example: `examples/schedulingTBD/data/projects/sur-craft-beer-4script.edn`

## Usage Patterns

### Pattern 1: AI-Driven Interview (Claude as Orchestrator)
```
User: "I need help scheduling my brewery"

Claude: [calls start_interview]
        [calls get_next_ds]
        → recommends "process/warm-up-with-challenges"
        [calls formulate_question]
        "What products do you make and what are your scheduling challenges?"

User: "We make craft beer..."

Claude: [calls interpret_response]
        [calls formulate_question]
        "Do your products follow the same production process?"

[Continue until DS complete]

Claude: [calls complete_ds]
        [calls get_next_ds]
        → recommends "process/flow-shop"
        [Process continues...]
```

### Pattern 2: Table-Based Collection
```
Claude: [calls formulate_table_question]
        "Please fill in your resource capabilities:"
        [presents HTML table]

User: [edits table with resource data]

Claude: [calls process_table_response]
        [validates and stores data]
        [continues with next question]
```

## Development Timeline

### Completed
- ✓ Phase 2.5: Core DS Flow (SCR → ASCR pipeline)
- ✓ Tool naming with agent prefixes
- ✓ Single surrogate session simplification

### Week 1: Table Infrastructure
- [ ] Create table-comm namespace
- [ ] Implement HTML generation/parsing
- [ ] Add table support to DS loader
- [ ] Create table-specific MCP tools
- [ ] Test with surrogate experts

### Week 2: MCP-UI Integration
- [ ] Research mcp-ui capabilities
- [ ] Design integration architecture
- [ ] Create proof-of-concept
- [ ] Adapt existing React table component if feasible
- [ ] Document UI integration patterns

### Week 3: LangGraph4j Exploration
- [ ] Create LangGraph4j proof-of-concept
- [ ] Map current flow to graph nodes
- [ ] Design conditional routing logic
- [ ] Implement state checkpointing with ASCR as reducer
- [ ] Test with complex scenarios

### Week 4: Agent Instructions & LLM Orchestration
- [ ] Create agent instruction documents
- [ ] Replace hard-coded `orch_get_next_ds` logic with LLM reasoning
- [ ] Implement orchestrator agent with proper instructions
- [ ] Test decision-making quality
- [ ] Document agent patterns

### Week 5: Integration and Documentation
- [ ] Integrate all components
- [ ] Update system documentation
- [ ] Create developer guides
- [ ] Performance testing
- [ ] Deploy updates

## Testing Strategy

### Surrogate Expert Testing

Using the existing surrogate expert system:

1. **Unit Tests**: Individual DS completion with predefined responses
2. **Integration Tests**: Full interview flows with surrogate experts
3. **Stress Tests**: All 1600 "How It's Made" manufacturing processes
4. **Validation Tests**: Compare generated models against known solutions

### Test Tools
```clojure
{:name "run_surrogate_interview"
 :description "Conducts automated interview with surrogate expert"
 :parameters {:surrogate_id :string
              :ds_sequence [:string]
              :max_questions :number}
 :returns {:completed_ds [:string]
           :ascr :object
           :transcript :object}}
```

## Success Metrics

1. **Functional Metrics**
   - Tables reduce question count by 50% for structured data
   - All existing DS work with new table features
   - LangGraph routing improves interview efficiency

2. **Technical Metrics**
   - Table parsing accuracy > 95%
   - Tool response time < 2 seconds
   - State recovery works reliably

3. **User Experience Metrics**
   - Reduced cognitive load for data entry
   - Clear visual feedback on progress
   - Intuitive table editing interface

## Risk Mitigation

1. **HTML Table Complexity**
   - Start with simple tables, add features incrementally
   - Provide clear examples in DS templates
   - Fallback to text questions if tables fail

2. **MCP-UI Compatibility**
   - Design table format to be UI-agnostic
   - Support both HTML and JSON representations
   - Test with multiple MCP clients

3. **LangGraph Learning Curve**
   - Start with simple linear flows
   - Add complexity gradually
   - Maintain backward compatibility

## Open Questions

1. **Orchestration Complexity**: Should orchestration logic be in tools or in Claude's reasoning?
2. **State Granularity**: How much state in DB vs. returned in tool responses?
3. **DS Evolution**: How to handle DS version changes over time?
4. **Multi-user Support**: Should we support collaborative interviews?
5. **Agent Configuration**: How to properly integrate agent descriptions into MCP project structure?

## Future Enhancements

1. **Advanced Table Features**
   - Conditional columns based on previous answers
   - Calculated fields and formulas
   - Import from CSV/Excel

2. **Visual DS Builder**
   - GUI for creating new DS templates
   - Drag-and-drop table design
   - Automatic validation rule generation

3. **Multi-modal Interaction**
   - Voice input for table data
   - Image upload for process diagrams
   - Sketch-to-table conversion

4. **Advanced Features**
   - Backchannel communication support
   - Dynamic DS creation
   - Machine learning for orchestration
   - Integration with schedulingTBD visualization
   - MiniZinc generation

## Conclusion

This development plan focuses on enhancing schedMCP with table-based communication while exploring advanced orchestration through LangGraph. By building on the existing LLM-powered foundation and leveraging our existing React table implementation, we can create a powerful system that combines the flexibility of natural language with the efficiency of structured data collection.

The phased approach allows for incremental progress while maintaining system stability. Each phase delivers value independently while building toward a comprehensive manufacturing scheduling specification system.

The architecture preserves the sophisticated Discovery Schema system while adapting it to MCP's tool-based paradigm. The key innovation is decomposing the complex protocol into discrete, composable tools that can be orchestrated by either AI agents or automated systems, providing flexibility while maintaining the domain expertise encoded in the Discovery Schema templates.
