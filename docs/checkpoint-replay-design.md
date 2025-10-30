# Checkpoint Replay Design with Mocking

## Overview

This document describes how to use checkpoints for interview replay without requiring live LLM or surrogate calls, using a mocking approach inspired by schedulingTBD's `mock.clj`.

## Current State

### What Works ✅

1. **Checkpoint Creation**: Captures interview state at each iteration
   - Saves to `data/checkpoints/{project-id}/{ds-id}/{timestamp}.edn`
   - Contains: ASCR, messages, budget, complete status
   - Properly serializes with `#inst` tags for Date objects
   
2. **Checkpoint Loading**: Round-trip save/load verified
   - EDN format, human-readable
   - All state information preserved

3. **REPL Control**: `(ckpt/enable-checkpointing!)` / `(ckpt/disable-checkpointing!)`

### Current Limitation ⚠️

When we try to resume from a checkpoint by calling interview nodes directly:
- Nodes expect `org.bsc.langgraph4j.state.AgentState` (Java object)
- Nodes call LLM/surrogate which times out in testing
- Need a way to replay without live external calls

## Solution: Mocking System

Based on `examples/schedulingTBD/src/server/scheduling_tbd/mock.clj`, we need:

### 1. Mock Response Storage

Store expected LLM responses alongside checkpoints:

```clojure
;; In checkpoint metadata
{:checkpoint/metadata 
 {:step "after-first-qa"
  :next-expected-responses 
  {:formulate-question 
   {:question "What information do you track about customer orders?"}
   
   :interpret-response
   {:scr {:areas-we-intend-to-discuss ["customer-orders" "materials-on-hand"]
          :inquiry-areas [{:inquiry-area-id "customer-orders" ...}]}}}}}
```

### 2. Mock Mode Control

```clojure
(ns sched-mcp.interviewing.mock
  (:require [sched-mcp.interviewing.checkpoint :as ckpt]))

(def mock-mode? 
  "When true, use mock responses instead of calling LLM"
  (atom false))

(def mock-responses
  "Map of checkpoint-id -> expected responses"
  (atom {}))

(defn enable-mock-mode! 
  "Enable mocking with responses from checkpoint"
  [checkpoint]
  (reset! mock-mode? true)
  (reset! mock-responses (:checkpoint/metadata checkpoint)))

(defn disable-mock-mode! []
  (reset! mock-mode? false)
  (reset! mock-responses {}))
```

### 3. Mock LLM Wrapper

Modify `sched-mcp.llm/call-llm` to check mock mode:

```clojure
(defn call-llm [prompt]
  (if @mock/mock-mode?
    ;; Return pre-recorded response
    (get-mock-response prompt)
    ;; Real LLM call
    (actual-llm-call prompt)))
```

### 4. Checkpoint Replay Function

```clojure
(defn replay-from-checkpoint
  "Resume interview execution from checkpoint using mocked responses"
  [checkpoint-file & {:keys [max-iterations] :or {max-iterations 5}}]
  (let [cp (first (ckpt/load-checkpoints 
                   (ckpt/file-checkpointer) 
                   project-id 
                   ds-id))
        initial-state (:checkpoint/state cp)]
    
    ;; Enable mocking with this checkpoint's responses
    (mock/enable-mock-mode! cp)
    
    (try
      ;; Run interview with mocked responses
      (run-interview initial-state {:max-iterations max-iterations})
      
      (finally
        (mock/disable-mock-mode!)))))
```

## Enhanced Checkpoint Format

### Current Format
```clojure
{:checkpoint/id "uuid"
 :checkpoint/timestamp #inst "2025-10-30..."
 :checkpoint/project-id :sur-craft-beer
 :checkpoint/ds-id :data/orm
 :checkpoint/iteration 3
 :checkpoint/state {...}
 :checkpoint/metadata {:step "iteration-3"}}
```

### Enhanced Format for Replay
```clojure
{:checkpoint/id "uuid"
 :checkpoint/timestamp #inst "2025-10-30..."
 :checkpoint/project-id :sur-craft-beer
 :checkpoint/ds-id :data/orm
 :checkpoint/iteration 3
 :checkpoint/state 
 {:ascr {...}
  :messages [{:from :system :content "Q1"}
             {:from :surrogate :content "A1"}]
  :budget-left 0.85
  :complete? false
  ...}
 
 ;; NEW: Mock responses for next steps
 :checkpoint/mock-responses
 {:formulate-question
  {:prompt-sent "..." ; What was sent to LLM
   :response-received "..." ; What LLM returned
   :parsed {:question "Next question to ask"}}
  
  :interpret-response
  {:prompt-sent "..."
   :response-received "..."
   :parsed {:scr {:inquiry-areas [...]}}}
  
  :get-answer
  {:question-sent "..."
   :surrogate-response "..."}}}
```

## Implementation Steps

### Phase 1: Capture Mock Responses During Real Interviews

1. **Modify interview nodes** to record LLM interactions:
   ```clojure
   (defn formulate-question [state]
     (let [prompt (build-prompt state)
           response (llm/call-llm prompt)
           parsed (parse-response response)]
       
       ;; Record for mocking
       (when @ckpt/enable-checkpoints?
         (swap! current-checkpoint-mock-responses
                assoc :formulate-question
                {:prompt-sent prompt
                 :response-received response
                 :parsed parsed}))
       
       (add-to-state state parsed)))
   ```

2. **Save mock responses with checkpoint**:
   ```clojure
   (defn save-checkpoint-with-mocks! [cp mock-responses]
     (ckpt/save-checkpoint! 
      (ckpt/file-checkpointer)
      (assoc cp :checkpoint/mock-responses @mock-responses)))
   ```

### Phase 2: Mock Mode for Replay

1. **Wrap LLM calls**:
   ```clojure
   (defn call-llm-with-mock [prompt node-name]
     (if @mock/mock-mode?
       (get-in @mock/mock-responses [node-name :response-received])
       (actual-llm-call prompt)))
   ```

2. **Wrap surrogate calls**:
   ```clojure
   (defn get-answer-with-mock [question]
     (if @mock/mock-mode?
       (get-in @mock/mock-responses [:get-answer :surrogate-response])
       (sur/answer-question surrogate question)))
   ```

### Phase 3: Replay Function

```clojure
(defn replay-interview-from-checkpoint
  "Replay an interview from a checkpoint using pre-recorded responses.
   
   Options:
   - :max-iterations - Maximum iterations to run (default: 5)
   - :verify-ascr? - Verify ASCR matches expected (default: true)"
  [checkpoint-path & {:keys [max-iterations verify-ascr?] 
                      :or {max-iterations 5 verify-ascr? true}}]
  
  (let [cp (-> checkpoint-path slurp edn/read-string)
        initial-state (:checkpoint/state cp)
        mock-responses (:checkpoint/mock-responses cp)]
    
    (println "=== Replaying from checkpoint ===")
    (println "Iteration:" (:checkpoint/iteration cp))
    (println "DS:" (:checkpoint/ds-id cp))
    (println "Messages:" (count (:messages initial-state)))
    (println "ASCR before:" (keys (:ascr initial-state)))
    
    ;; Enable mocking
    (mock/enable-mock-mode! mock-responses)
    
    (try
      ;; Run interview
      (let [result (run-interview-steps initial-state max-iterations)]
        (println "\n=== Replay Results ===")
        (println "ASCR after:" (keys (:ascr result)))
        (println "Budget:" (:budget-left result))
        (println "Complete?:" (:complete? result))
        
        ;; Optionally verify against expected
        (when verify-ascr?
          (verify-ascr-matches expected-ascr (:ascr result)))
        
        result)
      
      (finally
        (mock/disable-mock-mode!)))))
```

## Use Cases

### 1. Test Development

```clojure
;; Capture checkpoint during real interview
(ckpt/enable-checkpointing!)
(run-interview initial-state)

;; Load and replay in test
(deftest test-orm-interview-progression
  (let [cp-file "data/checkpoints/sur-craft-beer/orm/2025-10-30T16-01-38.edn"
        result (replay-interview-from-checkpoint cp-file :max-iterations 3)]
    (is (contains? (:ascr result) "inquiry-areas"))
    (is (> (count (:messages result)) 4))))
```

### 2. Debugging Failures

```clojure
;; Interview failed at iteration 7
;; Load checkpoint from iteration 6 and replay
(def cp (load-checkpoint-at-iteration 6))
(replay-interview-from-checkpoint cp :max-iterations 2)
;; Now you can step through and see what went wrong
```

### 3. Testing DS Changes

```clojure
;; Test how changes to DS affect interview
(def cp (load-checkpoint "path/to/checkpoint.edn"))

;; Modify DS
(update-ds! :data/orm {:new-field "..."})

;; Replay with new DS
(replay-interview-from-checkpoint cp :verify-ascr? false)
```

## Benefits

1. **No External Dependencies**: Tests don't need LLM API keys or surrogate
2. **Fast**: Replay is instant, no network calls
3. **Deterministic**: Same checkpoint always produces same result
4. **Debugging**: Step through interview logic without waiting for LLM
5. **Test Fixtures**: Real interview data becomes test fixtures

## Migration Path

1. **Short term**: Just capture checkpoints for inspection
2. **Medium term**: Add mock responses to checkpoints during capture
3. **Long term**: Full replay capability with verification

## Open Questions

1. How to handle non-deterministic LLM responses (temperature > 0)?
2. Should we store multiple possible responses per checkpoint?
3. How to version checkpoint format as interview nodes evolve?
4. Should replay allow "branching" - trying different responses at a checkpoint?

---

*Last updated: 2025-10-30*
