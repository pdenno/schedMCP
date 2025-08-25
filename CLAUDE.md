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

## Coding Rules

### Naming Conventions
- **Boolean variables**: End with `?` (e.g. `inv/active?`, `mock?`)
- **Mutating functions**: End with `!` (e.g. `update-db!`, `reset-state!`)
- **Diagnostic definitions**: Tag with `^:diag` metadata for REPL-only usage
- **File naming**: Filenames should be unique within the project.
- **Namespace aliases**: These should be short and `itools` not `interviewer-tools`. The same alias should be used in all files.

### Data Management
- **NEVER use dynamic variables** - there is almost never a case where they're needed
- **Prefer atoms** for persistent state instead of dynamic vars
  ```clojure
  ;;; Good
  (def mock? (atom false))

  ;;; Avoid
  (def ^:dynamic *mock-enabled* false)
  ```

### Data Structures
- Use maps, vectors, sets, and primitives to store data
- **Do NOT use lists** (sequences) for data storage
- Recursive navigation of structures uses `map?` and `vector?`

### Clojure defn
- It is (defn "comment" [args] ...) not (defn [args] "comment" ...). Not like common-lisp!

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

---
*Keep this file under 120 lines for quick loading. Last updated: $(date)*
