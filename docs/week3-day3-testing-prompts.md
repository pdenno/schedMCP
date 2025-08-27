# Week 3 Day 3: Testing and Prompt Refinement

## Overview

Today we focus on testing the LLM integration and refining prompts for better interview quality. The goal is to ensure our AI interviewer asks clear, contextual questions and accurately interprets responses.

## Testing Strategy

### 1. Unit Tests for LLM Functions
- **Prompt Structure Tests**: Verify prompts have correct format (system/user messages)
- **Context Inclusion Tests**: Ensure prompts include ASCR, budget, and DS info
- **Response Parsing Tests**: Verify JSON extraction from LLM responses

### 2. Integration Tests
- **Question Generation Flow**: Test full flow from DS → Question
- **Response Interpretation Flow**: Test Answer → SCR extraction
- **Error Handling**: Test graceful degradation when LLM fails

### 3. Prompt Quality Tests
- **Clarity**: Questions should be natural and easy to understand
- **Relevance**: Questions should target missing DS fields
- **Progression**: Follow-up questions should build on previous answers

## Running Tests

### Basic Test Suite
```bash
# Run all LLM tests
clojure -M:test -n sched-mcp.llm-test

# Run prompt refinement tests
clojure -M:test -n sched-mcp.llm-prompt-test

# Run with API key for full testing
export OPENAI_API_KEY="your-key"
clojure -M:test
```

### Interactive Testing in REPL
```clojure
;; Load test namespace
(require '[sched-mcp.llm-prompt-test :as lpt])

;; Run specific test
(clojure.test/test-var #'lpt/test-prompt-structure)

;; Test with real LLM (requires API key)
(do
  (require '[sched-mcp.llm-direct :as llm])
  (llm/init-llm!)
  
  ;; Test question generation
  (let [prompt (llm/ds-question-prompt
                {:ds (ds/get-cached-ds :process/warm-up-with-challenges)
                 :ascr {}
                 :budget-remaining 10})]
    (llm/complete-json prompt :model-class :mini)))
```

## Prompt Refinement Guidelines

### 1. Question Generation Prompts

#### Current Issues to Address:
- Questions may be too technical (mentioning EADS, SCR)
- May not build naturally on previous context
- Budget awareness could be clearer

#### Refinement Approach:
```clojure
;; BEFORE: Technical prompt
"Based on the EADS, generate a question to fill the scheduling-challenges field"

;; AFTER: Natural conversation prompt
"You're having a friendly conversation about scheduling challenges.
 They've already told you about: {{existing-challenges}}
 
 Ask a natural follow-up question to learn more about their 
 specific scheduling pain points. Keep it conversational."
```

### 2. Response Interpretation Prompts

#### Current Issues:
- May miss nuanced information in answers
- Confidence scoring needs calibration
- Ambiguity detection could be improved

#### Refinement Approach:
```clojure
;; Enhanced interpretation prompt structure
{:system "You are an expert at understanding manufacturing contexts.
          Extract structured data while preserving nuance."
 :examples [;; Few-shot examples of answer → SCR
            {:answer "Sometimes we have issues"
             :scr {:challenges ["unspecified-issues"]}
             :confidence 0.3
             :ambiguities ["'Sometimes' is vague" "Issues not specified"]}]
 :user "Extract data from: {{answer}}"}
```

## Prompt Templates to Refine

### 1. `resources/prompts/ds-question.txt`
Focus areas:
- Natural language generation
- Context awareness
- Progressive disclosure

### 2. `resources/prompts/ds-interpret.txt`
Focus areas:
- Robust extraction
- Confidence calibration
- Ambiguity detection

## Testing Checklist

### Phase 1: Basic Functionality ✓
- [x] LLM connection works
- [x] Basic prompt generation
- [x] JSON response parsing

### Phase 2: Quality Testing (Today)
- [ ] Prompt structure validation
- [ ] Context completeness checks
- [ ] Error handling scenarios
- [ ] Response quality metrics

### Phase 3: Refinement
- [ ] A/B test prompt variations
- [ ] Measure extraction accuracy
- [ ] Optimize for conversation flow
- [ ] Tune confidence thresholds

## Key Metrics to Track

1. **Question Quality**
   - Clarity score (subjective 1-10)
   - Relevance to DS fields
   - Natural conversation flow

2. **Extraction Accuracy**
   - Fields correctly identified
   - Values accurately extracted
   - Ambiguities properly flagged

3. **User Experience**
   - Questions per DS completion
   - Retry rate (unclear questions)
   - Completion time

## Next Steps

After testing and refinement:
1. Document optimal prompt patterns
2. Create prompt engineering guidelines
3. Build automated quality checks
4. Plan for Week 3 Day 4: Advanced interview flows

## Common Issues and Solutions

### Issue: Questions too technical
**Solution**: Add persona to system prompt: "You are a friendly consultant, not a database"

### Issue: Missing context in follow-ups  
**Solution**: Always include conversation history summary in prompts

### Issue: Over-extraction (hallucinating data)
**Solution**: Add explicit instructions: "Only extract what is clearly stated"

### Issue: Poor confidence calibration
**Solution**: Provide calibration examples in few-shot prompts
