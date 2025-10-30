# Checkpoint System Usage Guide

The checkpoint system captures intermediate states during LangGraph interview execution for debugging and test replay.

## Overview

**Problem**: When interviews fail with large tracebacks, it's hard to understand what went wrong and at which step.

**Solution**: The checkpoint system saves the interview state after each iteration to EDN files, making it easy to:
- Inspect what happened at each step
- Understand where failures occurred
- Replay interviews from specific points (future feature)
- Create test fixtures from real interview runs

## Quick Start

### Enable Checkpointing in REPL

```clojure
(require '[sched-mcp.interviewing.checkpoint :as ckpt])

;; Enable checkpoint saving
(ckpt/enable-checkpointing!)

;; Check status
@ckpt/enable-checkpoints?  ;; => true

;; Disable when done
(ckpt/disable-checkpointing!)
```

### Enable via Command Line

```bash
# Start with checkpointing enabled
clojure -M:dev -Dcheckpoint.enabled=true
```

### Enable via System Property in Code

```clojure
;; Set before starting the system
(System/setProperty "checkpoint.enabled" "true")
```

## How It Works

When `enable-checkpoints?` is true, `run-interview` automatically:

1. Captures the state after each LangGraph iteration
2. Saves it to `data/checkpoints/{project-id}/{ds-id}/{timestamp}-{id}.edn`
3. Includes metadata: iteration number, timestamp, thread-id, process-id

## Checkpoint File Structure

```clojure
{:checkpoint/id "fbf0453f-3aca-4f91-adb5-7a3460d06bad"
 :checkpoint/parent-id nil  ; or parent checkpoint ID
 :checkpoint/timestamp #inst "2025-10-30T15:30:13.781968967Z"
 :checkpoint/project-id :sur-craft-beer
 :checkpoint/ds-id :data/orm
 :checkpoint/iteration 3
 :checkpoint/thread-id "default"
 :checkpoint/process-id 1348441
 :checkpoint/metadata {:step "iteration-3"}
 :checkpoint/state
 {:ds-id :data/orm
  :pid :sur-craft-beer
  :cid :data
  :ascr {...}  ; Aggregated knowledge so far
  :budget-remaining 0.65
  :messages [...]  ; Conversation history
  :complete? false}}
```

## Inspecting Checkpoints

### List Available Checkpoints

```clojure
(require '[sched-mcp.interviewing.checkpoint :as ckpt])

(def file-cp (ckpt/file-checkpointer))

;; List checkpoint files
(ckpt/list-checkpoints file-cp :sur-craft-beer :data/orm)
;; => ["data/checkpoints/sur-craft-beer/orm/2025-10-30T15-30-13-781968967Z-fbf0453f.edn" ...]
```

### Load and Inspect

```clojure
;; Load all checkpoints for a project/DS
(def checkpoints (ckpt/load-checkpoints file-cp :sur-craft-beer :data/orm))

;; Count how many iterations
(count checkpoints)  ;; => 15

;; Get the latest checkpoint
(def latest (ckpt/latest-checkpoint checkpoints))

;; Get checkpoint at specific iteration
(def cp-5 (ckpt/checkpoint-at-iteration checkpoints 5))

;; Inspect summary (without full state)
(ckpt/inspect-checkpoint latest)
;; Prints:
;; === Checkpoint Summary ===
;; {:checkpoint/id "..."
;;  :checkpoint/timestamp #inst "..."
;;  :checkpoint/iteration 14
;;  ...}

;; Inspect with full state
(ckpt/inspect-checkpoint latest {:show-state? true})
```

### Analyze What Went Wrong

```clojure
;; Load checkpoints from a failed interview
(def cps (ckpt/load-checkpoints file-cp :failed-project :data/orm))

;; Check progression of ASCR accumulation
(doseq [cp cps]
  (println "Iteration:" (:checkpoint/iteration cp))
  (println "ASCR keys:" (keys (get-in cp [:checkpoint/state :ascr])))
  (println "Budget:" (get-in cp [:checkpoint/state :budget-remaining]))
  (println "---"))

;; Find where it stopped progressing
(def last-cp (ckpt/latest-checkpoint cps))
(get-in last-cp [:checkpoint/state :complete?])  ;; false?
(get-in last-cp [:checkpoint/state :budget-remaining])  ;; 0?

;; Examine the last few messages
(->> last-cp
     :checkpoint/state
     :messages
     (take-last 3)
     (map #(select-keys % [:from :content])))
```

## Using in Tests

### Testing Complete Interviews with Checkpoints

```clojure
(ns sched-mcp.my-test
  (:require
   [clojure.test :refer [deftest is]]
   [sched-mcp.interviewing.checkpoint :as ckpt]))

(deftest test-interview-with-checkpoints
  ;; Enable checkpointing for this test
  (ckpt/enable-checkpointing!)
  
  (try
    ;; Run your interview
    (let [result (run-interview initial-state)]
      (is (some? result))
      
      ;; Load and verify checkpoints
      (let [cps (ckpt/load-checkpoints 
                 (ckpt/file-checkpointer)
                 :test-project
                 :data/orm)]
        (is (> (count cps) 0) "Should have captured checkpoints")
        
        ;; Verify ASCR grew over time
        (is (< (count (get-in (first cps) [:checkpoint/state :ascr]))
               (count (get-in (last cps) [:checkpoint/state :ascr])))
            "ASCR should accumulate")))
    
    (finally
      ;; Disable to avoid affecting other tests
      (ckpt/disable-checkpointing!))))
```

### Testing Individual Nodes with Checkpoints

When testing nodes in isolation (outside of LangGraph execution), you need to handle state updates manually since the graph framework isn't running. Use `lgu/apply-node-update` to simulate LangGraph's state merging behavior:

```clojure
(ns sched-mcp.my-node-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.checkpoint :as ckpt]
   [sched-mcp.interviewing.interview-state :as istate]
   [sched-mcp.interviewing.interview-nodes :as nodes]
   [sched-mcp.interviewing.lg-util :as lgu]
   [sched-mcp.system-db :as sdb]))

(deftest test-interpret-response-node
  "Test interpret-response node in isolation using a checkpoint."
  (testing "Load checkpoint and call node"
    (let [;; Load checkpoint from actual interview
          file-cp (ckpt/file-checkpointer)
          cps (ckpt/load-checkpoints file-cp :test-project :data/orm)
          cp (ckpt/checkpoint-at-iteration cps 0)
          
          ;; Prepare state (add DS instructions if needed)
          state (:checkpoint/state cp)
          ds-obj (sdb/get-DS-instructions :data/orm)
          enriched-state (assoc state :ds-instructions 
                               {:DS ds-obj
                                :interview-objective (:DS/interview-objective ds-obj)})
          
          ;; Convert to AgentState
          istate-record (istate/map->InterviewState enriched-state)
          agent-state (istate/interview-state->agent-state istate-record)
          
          ;; Call node - returns update map {:ascr scr}
          update-map (nodes/interpret-response agent-state)
          
          ;; Apply update to get new AgentState (simulates LangGraph)
          new-agent-state (lgu/apply-node-update agent-state update-map)
          
          ;; Convert back to InterviewState for assertions
          result-istate (istate/agent-state->interview-state new-agent-state)]
      
      (is (seq (:ascr result-istate)) "ASCR should be populated")
      (is (< (:budget-left result-istate) 1.0) "Budget should decrease"))))
```

**Key Points:**

1. **Node Return Values**: Interview nodes return **update maps** (e.g., `{:ascr scr}` or `{:messages [...]}`) not AgentState objects
2. **LangGraph Merging**: Within the graph, these updates are automatically merged using the reducer channels
3. **Testing in Isolation**: Use `lgu/apply-node-update` to manually apply the update and get a new AgentState
4. **State Conversion**: The helper properly handles:
   - ASCR merging with `ds-combine` logic
   - Message appending
   - Budget and completion flag updates

## Directory Structure

```
data/
  checkpoints/
    {project-id}/          # e.g., sur-craft-beer
      {ds-id}/             # e.g., orm (namespace stripped)
        2025-10-30T15-30-13-781968967Z-fbf0453f.edn
        2025-10-30T15-30-14-123456789Z-a1b2c3d4.edn
        ...
```

Files are sorted by timestamp (filename), making it easy to:
- Find the latest checkpoint
- See progression over time
- Use standard shell tools (`ls`, `tail`, etc.)

## Performance Notes

- **Zero overhead when disabled**: If `@enable-checkpoints?` is false, no files are written
- **Small overhead when enabled**: State is serialized to EDN after each iteration
- **Not recommended for production**: Use for debugging and test fixtures only
- **Cleanup**: Checkpoint files persist until manually deleted

## Common Workflows

### Debugging a Failing Interview

```clojure
;; 1. Enable checkpointing
(ckpt/enable-checkpointing!)

;; 2. Run the failing interview
(def result (conduct-interview ...))

;; 3. Examine checkpoints
(def cps (ckpt/load-checkpoints (ckpt/file-checkpointer) pid ds-id))

;; 4. Find where it went wrong
(doseq [[prev curr] (partition 2 1 cps)]
  (let [prev-keys (keys (get-in prev [:checkpoint/state :ascr]))
        curr-keys (keys (get-in curr [:checkpoint/state :ascr]))]
    (when (= prev-keys curr-keys)
      (println "ASCR stopped growing at iteration" 
               (:checkpoint/iteration curr)))))

;; 5. Inspect the problem iteration
(def problem-cp (ckpt/checkpoint-at-iteration cps 7))
(ckpt/inspect-checkpoint problem-cp {:show-state? true})
```

### Creating Test Fixtures

```clojure
;; 1. Run a successful interview with checkpoints enabled
(ckpt/enable-checkpointing!)
(def result (conduct-interview ...))

;; 2. Extract interesting checkpoints
(def cps (ckpt/load-checkpoints (ckpt/file-checkpointer) pid ds-id))

;; 3. Save specific ones as test fixtures
(def mid-interview (ckpt/checkpoint-at-iteration cps 5))
(spit "test/fixtures/mid-orm-interview.edn" 
      (pr-str (:checkpoint/state mid-interview)))

;; 4. Use in tests
(def fixture-state (edn/read-string (slurp "test/fixtures/mid-orm-interview.edn")))
```

## Troubleshooting

### Checkpoints not being saved

- Check: `@ckpt/enable-checkpoints?` should be `true`
- Check: Run `(ckpt/enable-checkpointing!)` after reloading namespaces
- Check: File permissions on `data/checkpoints/` directory

### "No reader function for tag object"

- This has been fixed in the current implementation
- Instant objects are now properly serialized with `#inst` tags

### Too many checkpoint files

```bash
# Clean up old checkpoints
rm -rf data/checkpoints/old-project/

# Or clean by date
find data/checkpoints -name "*.edn" -mtime +7 -delete
```

## API Reference

### Core Functions

- `(enable-checkpointing!)` - Enable checkpoint saving
- `(disable-checkpointing!)` - Disable checkpoint saving
- `(file-checkpointer)` - Create default file checkpointer (data/checkpoints)
- `(file-checkpointer "path")` - Create with custom root directory

### Checkpoint Operations

- `(save-checkpoint! cp checkpoint-map)` - Save a checkpoint
- `(list-checkpoints cp project-id ds-id)` - List checkpoint files
- `(load-checkpoints cp project-id ds-id)` - Load all checkpoints

### Checkpoint Utilities

- `(make-checkpoint {:keys [state project-id ds-id iteration thread-id ...]})` - Create checkpoint map
- `(latest-checkpoint checkpoints)` - Get most recent checkpoint
- `(checkpoint-at-iteration checkpoints n)` - Get checkpoint at iteration n
- `(inspect-checkpoint cp)` - Pretty-print summary
- `(inspect-checkpoint cp {:show-state? true})` - Pretty-print with full state

## Integration with Existing Code

The checkpoint system is integrated into `run-interview` in `interview-graph.clj`:

```clojure
(run-interview initial-state
               {:checkpointer memory-saver        ; Optional LangGraph MemorySaver
                :thread-id "thread-123"           ; Required if checkpointer provided
                :file-checkpointer file-cp})      ; Optional, defaults to (file-checkpointer)
```

No code changes needed in most cases - just enable the atom!

---

*Last updated: 2025-10-30*
