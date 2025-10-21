This guide provides descriptions of relevant MCP tools and a practical step-by-step workflow for the LLM conducting interviews in schedMCP.

## Overview

schedMCP conducts structured interviews to understand manufacturing scheduling challenges and help users build scheduling solutions to those challenges using MiniZinc. 
Here we talk mostly about how to conduct the interviews.
Interviews involve three personas:
- **Orchestrator** (you, the LLM running MCP) - Selects Discovery Schemas (DS) and delegates interviewing with them to a LangGraph-based tool.
- **Interviewer** - Formulates questions and interprets responses
- **Interviewee** - a human expert or AI surrogate that responds to questions

## Key Concepts

### The Project's Project Database is Central to Your Work 

Your job is to orchestrate interactions between the interviewees and the system, collecting their requirements, building a solution, and explaining it.
The decisions you make in your orchestration effort are founded on what you learn about the project from the project and system DBs.
The details of querying these DBs are described below in section `Querying the DBs`, but first let's finish discussing key concepts. 

### Discovery Schema (DS)
DS are structured templates that guide interview information collection on specific topics. They are found in the system DB.  Examples:
- `process/warm-up-with-challenges` - Initial exploration
- `process/flow-shop` - Flow-shop scheduling details
- `data/orm` - Object-Role Modeling

### Schema-Conforming Response (SCR)
SCRs are structured data extracted from a single interview response that conforms to the DS template.

### Aggregated Schema-Conforming Response (ASCR)
Combined SCRs representing the complete state of information collected for a DS.

### Conversation ID
Interviews are organized by topic. The `conversation_id` is one of:
- `:process` - How they produce products/services
- `:data` - Data structures and relationships
- `:resources` - Physical resources and capabilities
- `:optimality` - What makes a "good" schedule

Note that the DS described above `process/flow-show`, `data/orm` use these topics as prefixes.

## Querying the Databases

You query the DBs to decide which DS to delegate to the interviewer; thereby you control the flow of discussion with the user.
You also query the DBs to answer whatever questions they might have, for example, `What was the fermentation time I suggested for a typical IPA?`.

There are two MCP tools used to query DBs, `db_query` and `db_resolve_entity`.

### The `db_query` tool

The `db_query` tool takes two arguments, `db_type` and `query_string` and runs the query against the designated DB.
- `db_type` is either "system" or "project" where "system" designates the system DB, and "project" designates the current project DB.
- `query_string` is a datalog-syntax string that can `edn/read-string` to a Clojure vector. 

```javascript
   db_query({"db_type" : "system", 
	         "query_string" : "[:find [?ds-id ...] :where [_ :DS/id ?ds-id]]"})
			 
			 
{
  "status" : "success",
  "query-result" : [ "process/job-shop--classifiable", 
	                 "process/flow-shop", 
					 "process/warm-up-with-challenges", 
					 "data/orm", 
					 "process/scheduling-problem-type", 
					 "process/timetabling", 
					 "process/job-shop", 
					 "process/job-shop--unique" ]
}
```
Notes about the example above:
- The query string always begins with `[:find`.
- The `:where` clause contains triples that unify to binding of `[<entity> <atttribute> <value>]` in the designated graph DB.
- `?ds-id` is a variable.
- `[?ds-id ...]` means create a vector of all the bindings to `?ds-id`.
- The `_` in `[_ :DS/id ?ds-id]` mean we aren't binding the entity to anything.
- How did you know to use the attribute `:DS/id`? Answer: There is an MCP resource you can consult that contains the DB schema for project and system DBs.

### DB Schema

Let's suppose you just made the query above and you wanted to further investigate the `process/warm-up-with-challenges` discovery schema.
Consulting the schema MCP resource, you see that the system DB contains the complete information about discovery schema:

```clojure
{,,,
   :DS/budget-decrement
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/double
        :doc "The cost of each question, decrementing againt a budget for the entire conversation."}
   :DS/id
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/keyword :unique :db.unique/identity
        :doc "A unique ID for each Discovery Schema. The namespace of the keyword is the cid, e.g. :process/flow-shop."}
   :DS/interview-objective
      #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "The objective of interviewing with this DS, (the string is also part of :DS/obj-str, but using this attribute, you can get just the objective, without all the detail."}
   :DS/obj-str
   #:db{:cardinality :db.cardinality/one, :valueType :db.type/string
        :doc "The stringified clojure object, it can be edn/read-string. It is the EDN version of the JSON used by the orchestrator"}
   :DS/can-produce-visuals
   #:db{:cardinality :db.cardinality/many, :valueType :db.type/keyword
        :doc "Indicates the purpose and instructions given."}
,,,}		

```
You could get the interview object, `:DS/interview-objective`, or the whole stringified `:DS/msg-str

```javascript

   db_query({"db_type" : "system", 
	         "query_string" : "[:find ?objective . :where [?e :DS/id :process/warm-up-with-challenges] [?e :DS/interview-objective ?objective]]"})

{"status": "success",
   "query-result":
 "This is typically the DS the orchestrator will use first.\nUse it in cases where there has been very little conversation so far, where the activities property of CONVERSATION-HISTORY is empty or nearly so.\nThe objective of this interview segment is to \n   1) get the interviewees started in discussing their scheduling problem, and,\n   2) make some basic observations about the scheduling challenges they face.\nThe first question to ask in this interview segment, which we call the 'warm up question' is simply this:\n\n 'What are the products you make or the services you provide, and what is the scheduling challenge involving them? Please describe in a few sentences.' \n\nCompleting this DS may require more than just that one question.\nExamine the DS to determine what other questions you may wish to ask."}
 ```
Notes about the example above:
- We bound `?e` in the first triple so that the query would only match the `:process/warm-up-with-challenges` entity in the system DB. Notice from the schema that `:DS/id` is `:unique :db.unique/identity`
  That means that the DB can only contain one entity with the `:DS/id` and that writing to it updates this entity.
- Notice that we used `[:find ?objective .`, the `.` means just return the first match. Of course, here there is only one because of the uniqueness constraint.
 
### The `db_resolve-entity` tool

The `db_resolve_entity` tool is typically used in conjunction with the `db_query` tool;  use the `db_query` tool to obtain a entity-id (`:db/id`), and then use `db_resolve_entity` to see
the DB tree under that entity. Both project DBs and the system DB are organized as trees, so it really is a tree and not an acyclic graph that is returned by the tool.

Example: Let's find all the ASCRs for the current project. 
#### STEP 1: Use `db_query` to get the entity-id of the project's `:project/ASCRs` attribute.

```javascript
   db_query({"db_type" : "project", 
             "query_string" : "[:find ?e . :where [?e :project/ASCRs]]"})
			 
{:status "success", :query-result 45}			 
```
Notes about the example above:
- Notice that we didn't use a complete triple: `[?e :project/ASCRs]`. You don't need to specify the value (3rd position) if you don't need it.
- `db_type` = `"project"` refers to the current project.
- The entity-id returned is 45. entity-id's are just positive integers.

#### STEP 2: Use `db_resolve_entity` to retrieve the tree under entity-id 45.

```javascript
   db_query({"db_type" : "project",
             "entity_id" : 45, 
             "drop_set" "#{:project/conversations}"})
			 
{
  "status" : "success",
  "query-result" : {
    "db/id" : 45,
    "project/ASCRs" : [ {
      "db/id" : 53,
      "ascr/budget-left" : 1.0,
      "ascr/completed?" : true,
      "ascr/id" : "process/warm-up-with-challenges",
      "ascr/str" : "{:one-more-thing \"They need to carefully plan fermentation schedules to avoid overlapping which can lead to resource bottlenecks.\", :scheduling-challenges [\"demand-uncertainty\" \"bottleneck-processes\" \"process-variation\" \"product-variation\" \"equipment-availability\"], :product-or-service-name \"craft beers\"}"
    } ],
    "project/active-conversation" : "process",
    "project/claims" : [ {
      "db/id" : 46,
      "claim/string" : "(project-id :sur-craft-beer)"
    }, {
      "db/id" : 47,
      "claim/string" : "(project-name :sur-craft-beer \"Integration Test\")"
    }, {
      "db/id" : 52,
      "claim/conversation-id" : "process",
      "claim/string" : "(surrogate :sur-craft-beer)"
    } ],
    "project/execution-status" : "running",
    "project/id" : "sur-craft-beer",
    "project/name" : "Integration Test"
  }
}
```
Notes about this example:
- Entity-id 45 is the **one** project entity in the DB, so what is returned is more than you wanted.
- The call included   `"drop_set"` =  `"#{:project/conversations}"` so that, at least the `:project/conversations` subtree was not included, the rest of the project was.
- `db/id` are included in this result; they can be useful for subsequent calls, but if you don't want them, include `:db/id` in the `drop_set`.
- There is also optional parameter `keep_set`. As you work with the `db_resolve_entity` tool or study the schema, you might learn ways to minimize verbosity.
  In this case,  `"drop_set` = `"#{:project/conversations :db/id}"`, `keep_set` =  `"#{:project/ASCRs :ascr/str}` produces the following concise result:

```javascript
  {"status" : "success",
   "query-result" : {
    "project/ASCRs" : [ {
      "ascr/str" : "{:one-more-thing \"They need to carefully plan fermentation schedules to avoid overlapping which can lead to resource bottlenecks.\", :scheduling-challenges [\"demand-uncertainty\" \"bottleneck-processes\" \"process-variation\" \"product-variation\" \"equipment-availability\"], :product-or-service-name \"craft beers\"}"
    } ]
  }
}


























==========================================================================================


## Complete Interview Workflow

<### Step 1: Initialize the the project


When the user asks to run an interview and provides a domain (e.g. plate glass), you start a surrogate expert in that domain that will be the **Interviewee**.

```
sur_start_expert({
  domain: "<description of the manufacturing domain>"
})
```
Returns `project_id` - use this for all subsequent calls.

### Step 2: Get the Next Discovery Schema

```
orch_get_next_ds({
  project_id: <pid>,
  conversation_id: <cid>
})
```

This returns:
- Available Discovery Schemas with their status
- Current ASCRs (what's been learned)
- Recommendations for what to pursue next

**Decision Making:** See  "MCP Orchestrator Guide for Directing the Course of Interviews" resource for detailed guidance on selecting which DS to pursue.

### Step 3: Start DS Pursuit

```
orch_start_ds_pursuit({
  project_id: <pid>,
  conversation_id: <cid>,
  ds_id: "<selected-ds-id>",
  budget: 10  // optional: max questions for this DS
})
```

The interviewer extracts structured data (SCR) from the natural language response.

### Starting Fresh (No Prior Work)

1. Start with `process/warm-up-with-challenges` (conversation: `:process`)
2. Then `process/scheduling-problem-type` (conversation: `:process`)
3. Based on problem type:
   - Flow-shop → `process/flow-shop`
   - Job-shop → `process/job-shop` variants
   - Timetabling → `process/timetabling`
4. Transition to data: `data/orm` (conversation: `:data`)
5. Eventually: resources and optimality topics

### Resuming Existing Work

1. Call `orch_get_progress({project_id: <pid>})` to see overall status
2. Review completed DS and current ASCRs
3. Select next appropriate DS based on what's missing

## Parameter Quick Reference

| Parameter | Type | Values/Examples |
|-----------|------|-----------------|
| `project_id` | keyword | `:project-123`, returned from `sur_start_expert` |
| `conversation_id` | keyword | `:process`, `:data`, `:resources`, `:optimality` |
| `ds_id` | string | `"process/warm-up-with-challenges"`, `"data/orm"` |
| `budget` | integer | `10` (optional, default varies) |
| `domain` | string | `"craft beer brewing"`, `"automotive parts"` |

## State Management

All interview state is persisted in the project database:
- Individual messages with their SCRs
- Current ASCR for each DS
- DS pursuit status (not-started, active, in-progress, completed)
- Conversation history

State is maintained across:
- Multiple question/answer cycles
- DS transitions
- System restarts
- Different conversation topics


## Additional Resources

- **MCP Orchestrator Guide** - Detailed guidance on DS selection strategy
- **PROJECT_SUMMARY.md** - System architecture and component descriptions  
- **README.md** - Installation and configuration


