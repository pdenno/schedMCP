# CLAUDE.md - AI Coding Copilot Instructions

Essential operating instructions for any LLM agent (Claude, GPT, etc.) working on this Clojure project.
Note that we are starting a new project in the src directory. As of this writing, there are only files using the utilities I typically use in src/ directory.
The new project should be based on an existing project schedulingTBD, found in the examples directory. As you will see, examples/schedulingTBD is a generative AI system for building scheduling systems using human/machine teaming.
The docs directory has some .pdfs about schedulingTBD.
The new project performs the same work as schedulingTBD but
 1) is built using Model Context Protocol (MCP), and
 2) borrows ideas about how to do that from examples/clojure-mcp, an MCP server for developing clojure.

Note that some of the instructions in this file concern schedulingTBD as it is implemented in examples/schedulingTBD.
We will write a new schedMCP/CLAUDE.md once we have a modest implementation in schedMCP/src.

## Starting up
- The system, including its MCP loop, is probably running when you join. If you clojure_eval `(develop.repl/ns-setup!)` you will have the same NS aliases that I typically use.
  You can then evaluate `(sutil/connect-atm :system)`. If that returns a connection object, such as `#<Connection@1840adbd: #datahike/DB {:max-tx 536870914 :max-eid 10}>`, you
  should be ready to work.

## Coding Rules

### Naming Conventions
- **Boolean variables**: End with `?` (e.g. `inv/active?`, `mock?`)
- **Mutating functions**: End with `!` (e.g. `update-db!`, `reset-state!`)
- **Diagnostic definitions**: Tag with `^:diag` metadata for REPL-only usage
- **File naming**: Filenames should be unique within the project, the exception being core.clj which can occur in each MCP tool directory.
- **Namespace aliases**: These should be short and `itools` not `interviewer-tools`. The same alias should be used in all files.
- **Some specific variables**:
      `pid` should be the only variable name used to refer to a project ID. Its value is a keyword.
      `cid` should be the only variable name used to refer to a conversation ID. Its value is a keyword in #{:process :data :resources :optimality}.

### Do not use println
- Do no use println. Currently, it interferes with MCP's JSON-RPC communication. Use log! instead (see util.clj). And remember: it takes two args:
 ```clojure
     ;;; First arg is reporting level. If more than two args, or just one more arg and it isn't a string, wrap them in str like this:
     (log! :info (str "Some text = " the-text))
     ```
### Short names for aliases
- alias names should be short, about 2-7 characters long.
 ```clojure
     (require '[sched-mcp.interviewers :as iviewrs])
     ;; NOT
     (require '[sched-mcp.interviewers :as interviewers])
     ```
### Data Management
- **Prefer atoms** to dynamic variable for persistent state.
  ```clojure
  ;;; Good
  (def mock? (atom false))

  ;;; Avoid
  (def ^:dynamic *mock-enabled* false)
  ```

### Data Structures
- Use maps, vectors, sets, and primitive data types to store data.
- **Do NOT use lists** (sequences) for data storage. We stipulate this because recursive navigation of structures uses `map?` and `vector?`.

### Clojure defn
- The comment comes before the arguments:
  ```clojure
      (defn my-fn
      "comment"
       [args]
      ...)

      ;; NOT
      (defn bad-fn [args]
        "comment"
        ...) ; It is not like common-lisp!
      ```
- **Use :diag or :admin metadata** on function definitions that are not referenced by other code and are only used at by developers and in the REPL.
  ```clojure
    (defn ^:diag run-me-in-repl
    []
    "Hi, Peter! No code calls me.")
    ```

## Editing Etiquette

### Surgical Changes Only
- Make **minimal, targeted edits** - don't reformat entire functions
- **Preserve existing spacing and indentation**
- **Respect whitespace patterns** in conditional forms:
  ```clojure
  ;; Preserve this spacing pattern
  (cond
      (test-1)                     1
      (much-longer-test)           2)

  ;; Don't collapse to single spaces
  ```

### Code Review Standards
- Maintain existing code style and patterns
- Don't introduce unnecessary formatting changes
- Focus edits on the specific functionality being modified

## Quick Reference

```clojure
;; Start system in the user directory
(start)

;; Check system status and explore
develop.repl/alias-map                          ; Map of namespaces keyed by consistently used aliases.
(clj-mcp.repl-tools/list-ns)                    ; List namespaces
(clj-mcp.repl-tools/list-vars 'some.namespace)  ; List functions
(clj-mcp.repl-tools/doc-symbol 'function-name)  ; Get documentation

;; Common patterns
(def active? (atom true))           ; Boolean atom
(defn update-state! [new-state] ..) ; Mutating function
(def ^:diag diag (atom nil))        ; Diagnostic helper
```

## schedMCP Design Decisions

### Independence from clojure-mcp
- We DO NOT reference clojure-mcp in our deps.edn
- Instead, we borrow design patterns and code structures from clojure-mcp
- Copy and adapt code rather than creating dependencies
- This keeps schedMCP self-contained and focused on scheduling domain

---
*Keep this file under 120 lines for quick loading. Last updated: $(date)*
