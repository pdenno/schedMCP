# Surrogate Expert Agent Design

## Overview

A surrogate expert agent simulates domain experts in manufacturing scheduling interviews, enabling automated testing and development of the interview system without requiring human experts.

We would like to be able to say something like "Let's start an interview with a surrogate in making craft beer." and from that
the system would create the structure such as shown in Phase 1 below, set up the agent and begin the interview.

You should look at the function `system-instruction` in examples/schedulingTBD/src/server/scheduling_tbd/surrogate.clj to understand what has been successful instructions in our experience.
I assume implementing this will involve writing an agent.md instructions. I'm not very familiar with these, but I notice that they can use color.
If we could have surrogate expert text displayed in orange that would be nice.

## Core Concepts

### Purpose
- **Testing**: Validate interview flows without human participants
- **Training**: Generate consistent scenarios for system improvement
- **Development**: Enable rapid iteration on Discovery Schemas
- **Demonstration**: Show system capabilities with realistic examples

### Architecture

```
┌─────────────────┐     ┌────────────────┐     ┌──────────────────┐
│   Interviewer   │────▶│ Conversation   │────▶│ Surrogate Expert │
│   (MCP Tools)   │     │   Management   │     │   (LLM Agent)   │
└─────────────────┘     └────────────────┘     └──────────────────┘
         │                      │                         │
         └──────────────────────┴─────────────────────────┘
                          Shared State
```

## Implementation Strategy

### Phase 1: Basic Expert Agent

1. **Expert Persona Definition**
   ```clojure
   {:expert-id "brewery-expert-1"
    :domain "craft-beer-brewing"
    :expertise-level :intermediate
    :company-profile {:name "Riverside Craft Brewery"
                      :size "medium"
                      :products ["IPAs" "Lagers" "Seasonal beers"]
                      :challenges ["tank scheduling" "ingredient variability"]}}
   ```

2. **LLM Integration**
   - Use OpenAI GPT-4 with system prompts
   - Maintain conversation context
   - Domain-specific knowledge injection

3. **Response Generation**
   ```clojure
   (defn generate-expert-response
     [expert-persona question conversation-history]
     ;; LLM call with context
     )
   ```

### Phase 2: Conversation State Management

1. **State Tracking**
   - Questions asked/answered
   - Information revealed
   - Consistency maintenance

2. **Memory Integration**
   - Integrate into the project schema as :message/from :surrogate.

### Phase 3: Table-Based Communication

1. **HTML Table Generation**
   - Use mcp-ui (see https://github.com/idosal/mcp-ui and https://mcpui.dev/)
   - Process flows
   - Resource matrices
   - Time/capacity tables

2. **Table Parsing**
   - Extract structured data from HTML
   - Validate completeness
   - Update conversation state

Example:
```html
<table>
  <tr><th>Tank</th><th>Capacity</th><th>Current Product</th></tr>
  <tr><td>Tank-1</td><td>500L</td><td>IPA</td></tr>
  <tr><td>Tank-2</td><td>300L</td><td>Lager</td></tr>
</table>
```

### Phase 4: Domain Knowledge Templates

1. **Manufacturing Patterns**
   ```clojure
   {:craft-beer {:processes ["mashing" "boiling" "fermenting" "packaging"]
                 :resources ["tanks" "kettles" "filters"]
                 :constraints ["temperature" "time" "contamination"]}}
   ```

2. **Complexity Levels**
   - Simple: Linear processes, few resources
   - Medium: Parallel processes, resource conflicts
   - Complex: Multi-stage, quality constraints, demand variability

## API Design

### Creating an Expert
```clojure
(create-surrogate-expert
  :domain "plate-glass"
  :company-size :large
  :complexity :medium)
```

### Running an Interview
```clojure
(run-automated-interview
  :project-id "test-glass-factory"
  :expert-domain "plate-glass"
  :discovery-schemas [:warm-up :flow-shop :resources])
```

### Expert Response Interface
```clojure
(defprotocol SurrogateExpert
  (answer-question [this question context])
  (provide-table [this table-request])
  (clarify [this clarification-request])
  (validate-understanding [this summary]))
```

## Integration Points

### With Existing System

1. **Database Integration**
   - Store expert definitions in system DB
   - Track automated interview sessions
   - Record generated test cases

2. **MCP Tool Integration**
   - New tool: `create_surrogate_expert`
   - New tool: `run_expert_interview`
   - Enhance: `formulate_question` to handle tables

3. **Discovery Schema Enhancement**
   - Table templates in schemas
   - Structured data extraction
   - Validation rules

### With OpenAI/LLM

1. **Prompt Engineering**
   ```
   You are a [expertise-level] expert in [domain] manufacturing.
   Your company: [company-profile]
   Current challenges: [challenges]

   Maintain consistency with previous answers: [history]
   Use tables when listing multiple items.
   ```

2. **Context Management**
   - Conversation history summarization
   - Fact consistency checking
   - Progressive detail revelation

## Testing Strategy

### Unit Tests
- Expert response consistency
- Table generation/parsing
- Domain knowledge accuracy

### Integration Tests
- Full interview flows
- Multi-DS conversations
- Edge case handling

### Validation Tests
- Compare with real expert interviews
- Domain accuracy verification
- Process completeness

## Benefits

1. **Development Speed**
   - Test Discovery Schemas instantly
   - No scheduling with human experts
   - Rapid iteration

2. **Consistency**
   - Repeatable test scenarios
   - Controlled complexity levels
   - Benchmarkable results

3. **Coverage**
   - Test multiple domains
   - Various company sizes
   - Different complexity levels

4. **Training Data**
   - Generate interview transcripts
   - Build pattern library
   - Improve system responses

## Implementation Timeline

### Week 1: Basic Expert Agent
- LLM integration
- Simple Q&A capability
- Domain templates

### Week 2: Conversation Management
- State tracking
- Context maintenance
- Consistency checking

### Week 3: Table Communication
- HTML generation
- Table parsing
- Structured data extraction

### Week 4: Advanced Features
- Multi-DS support
- Complex scenarios
- Validation tools

## Example Usage

```clojure
;; Create a craft beer expert
(def expert (create-surrogate-expert
              :domain "craft-beer"
              :name "Riverside Brewery"
              :size :medium))

;; Start automated interview
(def interview-id
  (start-automated-interview
    :project-id "test-brewery-1"
    :expert expert))

;; Run through warm-up DS
(run-discovery-schema
  :interview-id interview-id
  :ds-id :warm-up-with-challenges)

;; Check results
(get-interview-results interview-id)
```

## Future Enhancements

1. **Multi-Expert Scenarios**
   - Production manager perspective
   - Operations director perspective
   - Different knowledge levels

2. **Adversarial Testing**
   - Inconsistent answers
   - Missing information
   - Confused responses

3. **Learning Integration**
   - Improve based on real interviews
   - Pattern extraction
   - Domain model refinement

4. **Evaluation Metrics**
   - Response quality scoring
   - Completeness measurement
   - Realism assessment
