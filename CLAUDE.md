# CLAUDE.md - AI Coding Copilot Instructions

Essential operating instructions for any LLM agent (Claude, GPT, etc.) working on this Clojure project.
This project performs the functions of an existing project, schedulingTBD, found in the `examples/schedulingTBD` directory, but uses MCP.
As much as possible, it uses code from schedulingTBD, particularly its database functions and database schema.

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

### When demonstrating code to the user:
- **Avoid writing files of 'hacks' for demonstration** - If, for example, a function is needed that is not intended to become part of the system, enter it directly into the environment using clojure_eval, or,
   if you must, write it in a file in the `src/tmp_scratch` directory.
- Whenever you do create such functions or data that is not intended to be part of the system but is used soley for demo, tell the user you are doing that.

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
;; Start system in the `user` namespace, which uses `mount` a tool for loading and starting the system.
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
