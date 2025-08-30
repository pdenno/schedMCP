# Surrogate Expert Agent - User Guide

## Overview

The surrogate expert agent simulates domain experts in manufacturing scheduling interviews. This allows for automated testing and development of the interview system without requiring human experts.

## Quick Start

### Via Claude Desktop (MCP Tools)

1. **Start a surrogate expert**:
   - "Let's start an interview with a surrogate expert in craft beer"
   - "Create a plate glass manufacturing expert"
   - "I need a textile manufacturing surrogate expert"
   - Any manufacturing domain can be specified!

2. **Ask questions**:
   - The surrogate will respond with orange-colored text (🟠)
   - Answers are generated based on the domain and maintain consistency
   - Tables can be requested for structured data

### Via REPL

```clojure
;; Load the namespace
(require '[sched-mcp.surrogate :as surrogate])

;; Start any domain expert
(def session (surrogate/start-surrogate-interview
              {:domain :craft-beer
               :company-name "Mountain Peak Brewery"
               :project-name "Craft Beer Scheduling"}))

;; Or try other domains
(def session (surrogate/start-surrogate-interview
              {:domain :semiconductor-fabrication
               :company-name "Silicon Valley Chips Inc."}))

;; Ask questions
(surrogate/surrogate-answer-question
  {:project-id (:project-id session)
   :question "What are your main manufacturing processes?"})
```

## How It Works

The surrogate expert uses GPT-4 to generate realistic responses based on:

1. **Domain Context**: The LLM understands the manufacturing domain and generates appropriate processes, resources, and challenges
2. **Consistency**: Maintains conversation history to ensure consistent responses
3. **Specificity**: Provides concrete numbers, times, and quantities
4. **Realism**: Mentions actual scheduling challenges faced in that domain

## Any Domain Supported

You can specify any manufacturing domain. The LLM will generate appropriate expertise:

- `craft-beer` - Brewing processes, fermentation timing
- `plate-glass` - Continuous processes, float glass production
- `metal-fabrication` - Job shop scheduling, setup times
- `textiles` - Weaving, dyeing, finishing processes
- `food-processing` - Batch production, shelf life constraints
- `semiconductor` - Clean room scheduling, yield optimization
- `pharmaceuticals` - GMP compliance, batch tracking
- `automotive-parts` - Just-in-time delivery, line balancing
- ...any other manufacturing domain!

## MCP Tools

### `start_surrogate_expert`
Starts a new surrogate expert session.

**Parameters**:
- `domain` (required): "craft-beer", "plate-glass", or "metal-fabrication"
- `company_name` (optional): Name of the simulated company
- `project_name` (optional): Name for the interview project

**Example**:
```
Tool: start_surrogate_expert
Domain: craft-beer
Company name: Rocky Mountain Brewery
```

### `surrogate_answer`
Get an answer from the surrogate expert.

**Parameters**:
- `project_id` (required): From start_surrogate_expert
- `question` (required): Your question

**Example**:
```
Tool: surrogate_answer
Project ID: craft-beer-surrogate-123456
Question: What are your main scheduling challenges?
```

### `get_surrogate_session`
Debug tool to inspect session state.

### `list_surrogate_sessions`
List all active surrogate sessions.

## Table Communication

The surrogate expert can generate HTML tables when requested. Example:

**Question**: "Can you provide a table of your fermentation tanks?"

**Response**:
```
Here are our main fermentation tanks:

#+begin_src HTML
<table>
  <tr><th>Tank ID</th><th>Capacity</th><th>Type</th></tr>
  <tr><td>FV-01</td><td>500L</td><td>Unitank</td></tr>
  <tr><td>FV-02</td><td>500L</td><td>Unitank</td></tr>
  <tr><td>FV-03</td><td>1000L</td><td>Conical</td></tr>
</table>
#+end_src
```

## Session Management

- Each surrogate expert maintains conversation history
- Sessions persist in memory during the server runtime
- Multiple concurrent sessions are supported
- Sessions track revealed facts and maintain consistency

## Testing Workflows

### 1. Basic Interview Test
```clojure
(require '[sched-mcp.surrogate-test :as test])
(test/test-craft-beer-expert)
```

### 2. Discovery Schema Testing
```clojure
;; Start surrogate
(def expert (surrogate/start-surrogate-interview
             {:domain :craft-beer}))

;; Use with Discovery Schema tools
(formulate_question project_id conversation_id ds_id)
;; Get surrogate response
(surrogate_answer project_id question)
;; Interpret response
(interpret_response project_id conversation_id ds_id answer question)
```

### 3. Automated Testing
```clojure
(defn test-warm-up-ds [domain]
  (let [expert (surrogate/start-surrogate-interview {:domain domain})
        question "What are your scheduling challenges?"
        answer (surrogate/surrogate-answer-question
                {:project-id (:project-id expert)
                 :question question})]
    ;; Process with DS tools
    ...))
```

## Implementation Notes

1. **LLM Integration**: Uses GPT-4 with domain-specific prompts
2. **Consistency**: Maintains facts across conversation
3. **Orange Display**: Responses marked with 🟠 for visual distinction
4. **Project Creation**: Automatically creates project in system DB
5. **Session State**: Stored in memory (not persisted to DB currently)

## Future Enhancements

- Persistence of expert sessions across restarts
- More manufacturing domains
- Variable expertise levels (novice to expert)
- Adversarial testing mode
- Integration with full interview orchestration

## Troubleshooting

### "No expert session found"
- Ensure you're using the correct project_id
- Check active sessions with `list_surrogate_sessions`

### LLM Errors
- Verify OPENAI_API_KEY is set
- Check API rate limits
- Ensure GPT-4 access

### Empty Responses
- Check that the domain is supported
- Verify the question is well-formed
- Check session state with `get_surrogate_session`
