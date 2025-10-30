# Checkpoint Example: Resuming from First Q&A

This document shows how to use the checkpoint system to resume an interview from the first Q&A pair in `sur-craft-beer.edn`.

## What We Created

A checkpoint representing the state **after** the first question-answer pair but **before** interpretation:

- **File**: `data/checkpoints/sur-craft-beer/orm/2025-10-30T16-01-38-120Z-5f714210.edn`
- **State**: 2 messages (Q&A), empty ASCR, budget 0.95
- **Ready for**: `interpret-response` node to create first SCR

## Checkpoint Structure

```clojure
{:checkpoint/id "5f714210-1945-4a0a-8479-92b8bf501aa1"
 :checkpoint/timestamp #inst "2025-10-30T16:01:38.120-00:00"
 :checkpoint/project-id :sur-craft-beer
 :checkpoint/ds-id :data/orm
 :checkpoint/iteration 1
 :checkpoint/metadata {:step "after-first-qa-ready-to-interpret"
                       :description "State after first Q&A pair, ASCR empty, ready to call interpret-response node"}
 :checkpoint/state
 {:ds-id :data/orm
  :pid :sur-craft-beer
  :cid :data
  :ascr {}  ; Empty - not yet interpreted
  :messages 
  [{:from :system
    :content "To get started, could you list the kinds of data that you use to schedule production?..."}
   {:from :surrogate
    :content "Here's what we use today (mostly Google Sheets plus an Outlook/Google Calendar and a whiteboard):..."}]
  :budget-left 0.95
  :complete? false}}
```

## How to Resume Execution

### Option 1: Run Test (Recommended)

The test file `test/sched_mcp/interviewing/checkpoint_replay_test.clj` contains a complete example.

**From command line** (takes 40+ seconds due to LLM call):
```bash
clojure -M:test -n sched-mcp.interviewing.checkpoint-replay-test
```

**From REPL** (use with caution - may timeout):
```clojure
(require '[sched-mcp.interviewing.checkpoint-replay-test :as crt])
(crt/run-checkpoint-replay-test)
```

### Option 2: Manual REPL Steps

**Note**: Each LLM call takes 40+ seconds. The REPL may timeout, but you can check results afterward.

```clojure
;; 1. Load checkpoint
(require '[sched-mcp.interviewing.checkpoint :as ckpt])
(require '[sched-mcp.interviewing.interview-state :as istate])
(require '[sched-mcp.interviewing.interview-nodes :as nodes])
(require '[sched-mcp.system-db :as sdb])

(def file-cp (ckpt/file-checkpointer))
(def cps (ckpt/load-checkpoints file-cp :sur-craft-beer :data/orm))

;; Find the checkpoint
(def cp (first (filter #(= "after-first-qa-ready-to-interpret" 
                           (get-in % [:checkpoint/metadata :step]))
                       cps)))

;; Verify it
(println "Messages:" (count (get-in cp [:checkpoint/state :messages])))
(println "ASCR empty?" (empty? (get-in cp [:checkpoint/state :ascr])))
(println "Budget:" (get-in cp [:checkpoint/state :budget-left]))

;; 2. Prepare state for interpret-response
(def state (:checkpoint/state cp))

(def ds-obj (sdb/get-DS-instructions :data/orm))
(def ds-instructions {:DS ds-obj
                      :interview-objective (:DS/interview-objective ds-obj)})

(def enriched-state (assoc state :ds-instructions ds-instructions))

;; 3. Convert to AgentState (required by nodes)
(def istate-record (istate/map->InterviewState enriched-state))
(def agent-state (istate/interview-state->agent-state istate-record))

;; Verify conversion
(println "Is AgentState?" (instance? org.bsc.langgraph4j.state.AgentState agent-state))

;; 4. Call interpret-response (TAKES 40+ SECONDS - will likely timeout in REPL)
(println "Calling interpret-response - THIS WILL TAKE 40+ SECONDS")
;; NOTE: This may timeout in REPL but still complete in background
(def result-agent-state (nodes/interpret-response agent-state))

;; 5. Check results (run this after the call completes or times out)
(when (bound? #'result-agent-state)
  (def result-istate (istate/agent-state->interview-state result-agent-state))
  (println "\n=== Results ===")
  (println "ASCR populated?" (seq (:ascr result-istate)))
  (println "ASCR keys:" (keys (:ascr result-istate)))
  (println "Budget after:" (:budget-left result-istate))
  (when (seq (:ascr result-istate))
    (println "\nASCR content:")
    (clojure.pprint/pprint (:ascr result-istate))))
```

## Expected Results

When `interpret-response` completes successfully:

1. **ASCR is populated** with extracted information from the first answer
2. **Budget decreases** (e.g., from 0.95 to ~0.90)
3. **ASCR contains ORM-specific keys**: 
   - `"areas-we-intend-to-discuss"` - list of topics mentioned
   - `"inquiry-areas"` - detailed object definitions

Example ASCR after interpretation:
```clojure
{"areas-we-intend-to-discuss" 
 ["customer-demand" "recipes-process-plans" "tank-equipment" 
  "materials-on-hand" "packaging"]
 
 "inquiry-areas"
 [{:inquiry-area-id "customer-demand"
   :inquiry-area-objects 
   [{:object-id "customer-po" :definition "Customer purchase order"}
    {:object-id "sku" :definition "Product SKU identifier"}
    ...]}
  {:inquiry-area-id "materials-on-hand"
   :inquiry-area-objects [...]}]}
```

## What This Proves

✅ **Checkpoint → Resume flow works**:
1. Checkpoint captures exact state after Q&A
2. State can be loaded from EDN file
3. State can be converted to AgentState
4. Node can be called to resume processing
5. ASCR accumulates from loaded state

✅ **Practical use cases**:
- Debug why ASCR isn't accumulating
- Test DS changes without full interview
- Create test fixtures from real data
- Resume failed interviews

## Known Limitations

1. **REPL timeout**: LLM calls take 40+ seconds, REPL times out at 20s
   - Solution: Use test file or wait for background completion
   
2. **No mocking yet**: Each resume makes a real LLM call
   - Solution: Future work - implement mocking system (see `checkpoint-replay-design.md`)

3. **Manual conversion**: Must manually add DS instructions and convert to AgentState
   - Solution: Future work - helper function `replay-from-checkpoint`

## Next Steps

To make this more practical:

1. **Add helper function**:
   ```clojure
   (defn replay-from-checkpoint 
     [checkpoint-file ds-id]
     ;; Handles all the setup automatically
     ...)
   ```

2. **Implement mocking** (see `docs/checkpoint-replay-design.md`):
   - Store expected LLM responses in checkpoint
   - Enable mock mode for testing
   - Replay without API calls

3. **Add to `run-interview`**:
   - Option to start from checkpoint instead of fresh state
   - Continue interviewing from saved state

---

*Last updated: 2025-10-30*
