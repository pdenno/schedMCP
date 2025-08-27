# Week 3-4 Implementation Plan

## LLM Integration and Advanced Orchestration

### Overview
Now that we have the core infrastructure working, we'll add intelligence to the system by:
1. Integrating LLMs into interviewer tools
2. Implementing sophisticated orchestration logic
3. Adding budget tracking
4. Supporting multi-interviewer coordination

### Week 3: LLM Integration

#### Day 1-2: LLM Infrastructure
- [ ] Create LLM client abstraction (OpenAI/Azure/Claude)
- [ ] Add prompt management system
- [ ] Integrate agent descriptions from `docs/agents/`
- [ ] Set up prompt templates for DS interviews

#### Day 2-3: Formulate Question Tool
- [ ] Load agent prompts for process interviewer
- [ ] Create prompt that includes:
  - DS template (EADS)
  - Current ASCR
  - Interview objective
  - Question history
- [ ] Parse LLM response into structured question format
- [ ] Add contextual help generation

#### Day 3-4: Interpret Response Tool  
- [ ] Create extraction prompt template
- [ ] Map natural language to DS schema fields
- [ ] Handle ambiguous responses
- [ ] Generate confidence scores
- [ ] Extract multiple SCR fields from one answer

#### Day 4-5: Testing & Refinement
- [ ] Test with real DS examples
- [ ] Tune prompts for accuracy
- [ ] Handle edge cases
- [ ] Add retry logic for failed extractions

### Week 4: Advanced Orchestration

#### Day 1-2: Budget Tracking
- [ ] Implement question counting per pursuit
- [ ] Add budget enforcement
- [ ] Create budget allocation strategies
- [ ] Handle budget exhaustion gracefully

#### Day 2-3: Smart DS Selection
- [ ] Analyze ASCR completeness
- [ ] Implement dependency resolution
- [ ] Add priority weighting
- [ ] Create recommendation explanations

#### Day 3-4: Multi-Interviewer Support
- [ ] Route DS to appropriate interviewer type
- [ ] Handle interviewer handoffs
- [ ] Coordinate shared context
- [ ] Implement interviewer-specific strategies

#### Day 4-5: Integration Testing
- [ ] End-to-end interview simulations
- [ ] Multi-DS conversation flows
- [ ] Performance optimization
- [ ] Error handling improvements

### Technical Design

#### LLM Client Design
```clojure
(defprotocol LLMClient
  (complete [this prompt options])
  (extract [this prompt schema])
  (classify [this text categories]))

(defrecord OpenAIClient [api-key model]
  LLMClient
  ...)
```

#### Prompt Template Structure
```clojure
{:system "You are a process interviewer..."
 :context {:ds {...} :ascr {...}}
 :instruction "Generate a natural question..."
 :format "Return JSON with fields: question, help, rationale"}
```

#### Budget Management
```clojure
{:total-budget 50
 :allocation {:warm-up 5
              :problem-type 3
              :flow-shop 20
              :data 10
              :optimization 12}}
```

### Key Challenges to Address

1. **Prompt Engineering**
   - Balance between structure and naturalness
   - Consistent extraction accuracy
   - Handling partial/ambiguous responses

2. **Context Management**
   - Efficiently summarize conversation history
   - Maintain coherent narrative across DS
   - Avoid repetitive questions

3. **Error Handling**
   - LLM API failures
   - Invalid extractions
   - Budget overruns

4. **Performance**
   - LLM call latency
   - Parallel processing opportunities
   - Caching strategies

### Success Metrics

1. **Question Quality**
   - Natural, conversational tone
   - Relevant to DS objectives
   - Progressive information gathering

2. **Extraction Accuracy**
   - 90%+ field mapping accuracy
   - Handling of complex responses
   - Minimal clarification needed

3. **Interview Efficiency**
   - Complete DS within budget
   - Minimal redundant questions
   - Smooth transitions between DS

4. **User Experience**
   - Feels like talking to expert
   - Clear value from each question
   - Natural conversation flow

### Implementation Priority

1. **Start with process interviewer** (most developed agent docs)
2. **Focus on warm-up and problem-type DS** (simpler schemas)
3. **Get basic LLM flow working** before optimization
4. **Add sophistication incrementally**

Let's start with the LLM infrastructure!