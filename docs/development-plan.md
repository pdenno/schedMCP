# schedMCP Development Plan - Phase 3 and Beyond

## Executive Summary

This document outlines the development plan for schedMCP, focusing on Phase 2.5 Discovery Schema flow, 2.6 Improving the ds-combine! methods used to combine SCRs to an ASCR, and then thoughts about Phase 3 development.

**Important Update**: Before implementing advanced features, we need to complete Phase 2.5 - ensuring the core Discovery Schema flow works properly with SCR storage and ASCR aggregation.
This is a prerequisite for all subsequent phases.

The project has successfully implemented basic interview functionality with LLM-powered question generation and response interpretation.
The next phase will enhance the system with structured data collection capabilities while maintaining the flexibility of natural language interaction.

## System Architecture Overview

### Core Concepts

1. **Runs on an MCP client** - All the tasks that SchedMCP performs are orchestrated by an MCP client.

2. **Discovery Schema (DS)** - JSON/EDN structures that define the information to be collected during interviews. These replace the deprecated term "EADS" (Example Annotated Data Structures).

3. **Schema-Conforming Response (SCR)** - Structured data extracted from individual user responses that conforms to the DS template.

4. **Aggregated Schema-Conforming Response (ASCR)** - Combined SCRs that represent the complete state of information collected for a DS.

5. **Orchestration** - The process of selecting and sequencing DS interviews based on collected information and domain logic. In the current implementation is is performed by Claude.

6. **clojure-mcp integration** - The MCP client can run all the clojure-mcp tools, prompts and resources to develop the system. Future work will make loading and unloading these dynamic; our MCP clients are quite ready for that, it seems.

## Current Implementation Status: Completed Features

1. **Basic Interview Flow (Phase 1)**
   - Warm-up phase with scheduling challenges questions
   - Question/answer mechanics via MCP tools
   - State persistence using Datahike

2. **Discovery Schema Infrastructure (Phase 2)**
   - 8 DS templates loaded from JSON:
	 - Process: warm-up, flow-shop, job-shop (3 variants), timetabling
	 - Data: ORM (Object-Role Modeling)
   - ASCR (Aggregated Schema-Conforming Response) management

3. **LLM Integration**
   - `iviewr_formulate_question`: Generates contextual questions from DS + ASCR
   - `iviewr_interpret_response`: Extracts structured data from natural language
   - Integration with OpenAI GPT-4

4. **Surrogate Scheduling Domain Expert System**
   - LLM-based domain expert simulation
   - Supports any given manufacturing domain where LLMs are likely to have at least passing competence.
   - Maintains conversation consistency
   - Plans for testing with a database of episode segment names from the Science Channel's "How It's Made" (~1600 manufacturing processes)

5. **Orchestration Foundation**
   - Orchestration is a key capability implemented our MCP components.
   - Currently, it is limited to selecting next Discovery Schema to delegate detailed interviewing to an interviewer.
   - Plans (described in this document) to develop comprehensive capabilities (See section `### Plans for Comprehensive Orchestration`)

6. We have an existing React table implementation in `examples/schedulingTBD/src/app/stbd_app/components/table2.cljs` that uses MUI DataGrid. This component already handles:
   - Table rendering with editable cells
   - Row addition/removal
   - Row reordering
   - Data transformation between internal format and display format

   This existing implementation can serve as a reference or potentially be adapted for the MCP-UI integration.

## Recent Architectural Decisions (September 2025)

Based on our discussion, we've made several important decisions:

### 1. Tool Naming with Agent Prefixes ✓ COMPLETED
Tools now have clear agent prefixes to indicate ownership:
- **Interviewer tools** (`iviewr_`): formulate_question, interpret_response,
- **Orchestrator tools** (`orch_`): get_next_ds, start_ds_pursuit, complete_ds, get_progress
- **Surrogate tools** (`sur_`): start_expert, answer, get_session
- **Shared tools** (`sys_`): get_current_ds, get_interview_progress

This naming convention immediately tells you which "persona" should be using each tool.

### 2. Single Surrogate Session ✓ COMPLETED
We simplified from multiple concurrent surrogate sessions to a single active session:
- Removed `list_surrogate_sessions` tool
- Changed `expert-sessions-atom` to `current-expert-session`
- Dramatically simplified the mental model

### 3. "Phase 2.5" Tasks ✓ COMPLETED (September 13, 2025)

Phase 2.5 involved testing entire cycles of the following 6 tasks:
1. ✅ Orchestrator persona selecting a Discovery Schema (DS)
2. ✅ Interviewer persona analyzing the DS and existing conversation to formulate a question
3. ✅ Surrogate expert persona responding to the question
4. ✅ Interviewer persona analyzing the surrogate interviewee's response and producing a Schema-Conforming Response (SCR)
5. ✅ Orchestrator using the DS, past ASCR (if any) and SCR to produce an updated ASCR using the `ds-combine!` method
6. ✅ All of the above being reflected in the project DB

**Status: COMPLETED**
- ✅ SCRs are now properly stored with messages using `pdb/put-msg-SCR!`
- ✅ ASCRs are correctly stored as Clojure data structures (not TxReports)
- ✅ Removed problematic `:conversation-intro` messages
- ✅ Added `:message/pursuing-DS` to track which DS a message relates to
- ✅ Implemented `base-iviewr-instructions.md` prompt system that:
  - Properly separates DS examples from actual domain
  - Uses consistent `{question-to-ask: "..."}` format for questions
  - Returns SCRs directly as the response
  - Handles the comment/val annotation structure correctly
- ✅ Successfully tested full cycle with craft beer surrogate expert.
	 (No significant orchestration demonstrated, however. That is, Claude did not choose any subsequent DSs.)

The system now correctly:
- Formulates domain-appropriate questions (no more plate glass confusion)
- Interprets responses into proper SCRs
- Stores both SCRs and ASCRs in the database
- Detects DS completion and updates status accordingly

### 4. Phase 2.5.5 Tasks: ds-combine!

- ✅ Redesign ds-combine! (now ds-combine) and ds-complete?.
   The dsu/ds-combine! methods were poorly designed. They should take only a SCR and an ASCR and it should return an ASCR.
   There should be no actions on the DBs to make this happen. We'll fix this first. (We'll call it ds-combine (no exclamation mark) since it won't touch the DB.)

### 5. "Phase 2.6" Tasks: Using clojure-mcp in sched-mcp

#### Completed (2025-09-28):
- ✅ **Fixed integration issues between schedMCP and clojure-mcp:**
  - Corrected path casing issues in mcp_core.clj (schedMCP → schedMCP)
  - Updated make-resources and make-prompts functions to use direct function calls instead of resolve
  - Verified all configuration paths are consistent

- ✅ **Verified clojure-mcp tools integration:**
  - All 17 clojure-mcp tools are loading correctly in schedMCP
  - Tools include: LS, read_file, grep, glob_files, think, clojure_inspect_project, clojure_eval, bash, clojure_edit, clojure_edit_replace_sexp, file_edit, file_write, dispatch_agent, architect, code_critique, clojure_edit_agent, scratch_pad
  - Successfully executed comprehensive test suite: 58 tests with 579 assertions - all passing with 0 failures

- ✅ **Verified MCP resources functionality:**
  - Resources loading properly (4 resources total)
  - Successfully loading: PROJECT_SUMMARY.md, README.md, CLAUDE.md, and dynamic "Clojure Project Info"
  - Resource tests passing: 8 tests with 51 assertions

- ✅ **Confirmed combined tool registry:**
  - SchedMCP tools (9): iviewr_formulate_question, iviewr_interpret_response, sys_get_current_ds, orch_get_next_ds, orch_start_ds_pursuit, orch_complete_ds, orch_get_progress, sur_start_expert, sur_answer
  - Clojure-mcp tools (17): All tools listed above
  - Total: 26 tools successfully registered and available

#### Remaining "Phase 2.6" Tasks (October, 2025):

- **This is the problem we are currently investigating.** :The client (Claude Desktop) is not able to see any of resources that should have been registered.
   (I believe `README.mc`, `CLAUDE.md`, `PROJECT_SUMMARY.md` and `LLM_CODING_STYLE.md` and `Clojure Project Info (dynamic)` are registered.)
   I wrote `sched_mcp.mcp_core/reload-components`, which should be helpful to debugging this problem and development generally, if it doesn't cause the MCP client to lose connection.
   Two immediate tasks WRT this are:
   1. Investigate the error generated by its execution, **SLF4J/ERROR  : - Operator called default onErrorDropped**, and
   2. Determine whether, even with the above corrected it causes the MCP client to lose connection.
   
   Footnote: We (and clojure-mcp) use the the term "MCP components" to refer collectively to MCP tools, prompts and resources.

- When the above is working: Make schedMCP work as stand alone.
  Currently if you start schedMCP using `clojure -M:dev -m sched-mcp.main` it produces output from startup as below, and then immediate exits.
  That's what we coded but not what we want!
  I tried adding a promise and await it. Perhaps that helps, but the client is still disconnecting.

#### Take another pass at ds-combine!
- To Do: More comprehensively, I think there is occassionally the need for an LLM-based agent to perform the combination, if the SCR's come back from the interviewer messed up, for example.
  The problem is it isn't clear how we should manage this. We can easily define a dsu/ds-valid? method on each DS; it returns true if
  the SCR or ASCR is structurally valid. I suppose that is part of the solution.
- The question: How would we best try the deterministic code dsu/ds-combine and only if dsu/ds-valid? returns false call the agent?
  Perhaps the agent is not an MCP tool, but maybe there a better way with an MCP tool or two?

## Post-2.6 Architecture Discussion

We need to determine where to take things next. (I will describe some ideas for this section later.)

### Plans for Comprehensive Orchestration

- Currently, orchestration is simplistic; it is restricted to selecting among a few discovery schema to direct interviewing.
- A more comprehensive viewpoint on 'orchestration' is that it is about influencing how interaction with users proceeds in **all** aspects of collaboration. Specifically, this includes,
	1. driving interviews forward by selecting discovery schema and delegating to the interviewer(as we do now),
	2. initiating validation tasks using diagrams created from ASCRs.
	3. refining and testing the  MiniZinc model as we learn more about the domain problem from the interviewees (users),
	4. mentoring users on how the system works, how it integrates with their data, and how MiniZinc works to solve their scheduling problem, and
	5. quizzing users to help them assess how well they understand the system. (This might be implemented as separate mode of operation from the previous 4 responsibilities.)
- We think the key to implementing this more comprehensive notion of orchestration might hinge on providing comprehensive MCP tools, prompts, and resources about the project and our scheduling expertise. For example:
	- An *MCP Tool* that is feature rich for allowing inspection of the project database.
	- An *MCP Resource* that describes the schema of the project database so as to support the


### Discuss Design for more-encompassing activity
 - Our plan for this system is that it can
	1. Mentor users in use of the system and the MiniZinc technology
	2. Quiz users to ensure that they 'stay in the loop' about the solution,
	3. Present diagrams for verification and validation (V&V) illustrating our interpretation of what the inteviewees have told us about their processes and challenges,
	4. Integrate itself into the existing production system architecture. For example, it might need to make API calls or upload spreadsheets to obtain customer orders.
	5. Provide an an agile, interative-refinement process (Start with the most rudimentary MiniZinc solution possible, for example, running through a manufacturing process with just one product
	   and refine the solution through interleaved discussion and mentoring.)
 - The above requirements raises questions about how the system operates. Is it all performed through MCP? Is there a role for LangGraph? MCP UI? What else?

###  Discuss LangGraph4j for Orchestration (On hold until we investigate the 'Plans for Comprehensive Orchestration' above)
LangGraph can enhance the orchestration layer by providing:
- Dynamic flow control based on interview responses
- Parallel execution of independent DS
- Backtracking when contradictions are discovered
- State checkpointing for complex interviews
- LangGraph provides dynamic flow control based on responses and enables backtracking and state checkpointing.
- Is it a natural fit for the ASCR accumulation pattern?

## MCP-UI Integration Strategy

### 1. Research Phase (Week 2)

- Study mcp-ui documentation at https://mcpui.dev/
- Analyze how our existing React table component could be adapted
- Design integration architecture

### 2. Proof of Concept (Week 2)

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

### 3. Integration Architecture (Week 2-3)

- MCP server provides table specifications
- UI renders editable tables client-side
- Validation happens both client and server side
- Results returned through standard MCP channels

## LangGraph Integration Exploration

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

## State Management Architecture

Since MCP tools are stateless, robust state management is critical.
We believe the project DBs are sufficient for managing conversation state (and project state more generally).

### Key State Management Insights

1. **Stateless Tool Challenge**: Every tool call used in interviewing receives DS template + current ASCR + conversation history.
2. **Pure Function Design**: Tools are `(DS, ASCR, input) → (action, updated_ASCR)`
3. **State Persistence**:
   - Individual SCRs stored in the project's DB with each response.
   - The corresponding ASCR is with each response.
   - See example: `examples/schedulingTBD/data/projects/sur-craft-beer-4script.edn`

## Testing Strategy

### Surrogate Expert Testing

Using the existing surrogate expert system:

1. **Unit Tests**: Individual DS completion with predefined responses
2. **Integration Tests**: Full interview flows with surrogate experts
3. **Stress Tests**: All 1600 "How It's Made" manufacturing processes
4. **Validation Tests**: Compare generated models against known solutions

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
2. **DS Evolution**: How to handle DS version changes over time?
3. **Agent Configuration**: How to properly integrate agent descriptions into MCP project structure?
