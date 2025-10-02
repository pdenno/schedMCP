# CLAUDE.md - AI Coding Copilot Instructions

Essential operating instructions for any LLM agent (Claude, GPT, etc.) working on this Clojure project.
The system is started on Claude Desktop as specified in `claude_desktop_config.json`.
The equivalent way to start the system without Claude involvement is to run `clojure -M:dev` from a shell or emacs and run `(start)` from the user namespace.
Even when started from Claude, the developer can nREPL-connect to the running systems (e.g. using emacs M-x cider-connect).

## Documents to read (in the `docs` directory.
  1. development-plan.md

## Starting up
- The system, including its MCP loop, is probably running when you join. If you
  To check that things are as they should be:
  1. `clojure_eval` (an MCP tool) the sexp `(develop.repl/ns-setup!)`; That will allow you to use the same NS aliases that I typically use.
  2. `clojure_eval` the sexp `(sutil/connect-atm :system)`; it should return a connection object, such as `#<Connection@1840adbd: #datahike/DB {:max-tx 536870914 :max-eid 10}>`, and
  3. `clojure_eval` the express `@mcore/components-atm apv :name))`, it should return a clojure map with three keys `:tools`, `:prompts` and `:resources` the values of each are vectors of maps describing the associated components.

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
- **Isolate use of Datahike to a few files** There is pretty much nothing persistent that doesn't belong in code, the system DB, or a project DB.
  Therefore, avoid writing Datahike queries and pulls in all files except `src/sched-mcp/system_db.clj`, `src/sched-mcp/project_db.clj`, and `src/sched-mcp/sutil.clj`.
- Whenever you need state, look into those files and see if something there is appropriate, if not, add a function to whichever of the three above is appropriate.

### Data Structures
- Use maps, vectors, sets, and primitive data types to store data.
- **Do NOT use lists** (sequences) for data storage. We stipulate this because recursive navigation of structures uses `map?` and `vector?`.
- **Do NOT use clojure.walk/stringify-keys** See sutil/stringify-keys.

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

## schedMCP Design Decisions

### Independence from clojure-mcp
- We DO NOT `require` (the Clojure namespace form) clojure-mcp namespace in the `ns` form.
 Instead, we `require` and `resolve` in functions that are only called if the configuration intends to run clojure-mcp (the MCP-based programming assistant).

---
*Keep this file under 120 lines for quick loading. Last updated: $(date)*
