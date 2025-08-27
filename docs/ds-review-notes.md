# Discovery Schema Review Notes

## Task 5: Review of DS/EADS Examples

### Common Structure Patterns

All Discovery Schemas follow this top-level structure:
```json
{
  "message-type": "EADS-INSTRUCTIONS",
  "interview-objective": "Description of what this DS is for...",
  "EADS": {
    "EADS-id": "domain/schema-name",
    ...schema-specific properties...
  }
}
```

### Property Patterns

Properties in DS can be:
1. **Simple values with comments**:
   ```json
   "property-name": {
     "val": "actual value",
     "comment": "Instructions for the interviewer..."
   }
   ```

2. **Direct values** (without comments):
   ```json
   "property-name": "direct value"
   ```

3. **Complex nested structures** (like ORM inquiry areas)

### Schema Types Observed

#### 1. Process Domain Schemas

**Warm-up with Challenges** (`process/warm-up-with-challenges`):
- Simple structure with 3 properties
- Focuses on identifying scheduling challenges using predefined keywords
- No user questions for challenge keywords - inferred from conversation

**Flow Shop** (`process/flow-shop`):
- Models sequential production processes
- Properties: process-id, inputs, outputs, resources, duration, subprocesses
- Hierarchical process structure with sub-processes

**Scheduling Problem Type** (`process/scheduling-problem-type`):
- Classifies the type of scheduling problem
- Properties: principal-problem-type, problem-components, continuous?, cyclical?
- Uses enumerations for problem types

#### 2. Data Domain Schemas  

**ORM** (`data/orm`):
- Most complex structure observed
- Uses inquiry areas → objects → fact types hierarchy
- Three-task process:
  1. Enumerate areas of inquiry
  2. Define ORM fact types
  3. Create example data tables
- Heavy use of reference modes and constraints

### Key Insights

1. **Annotation Pattern**: Most properties have both `val` and `comment`, where comment provides interviewer instructions

2. **Exhausted Flag**: Several schemas have an `exhausted?` property that lets the interviewer indicate completion

3. **ID Fields**: Properties ending in `-id` are used for unique identification and upsert operations during SCR aggregation

4. **Flexible Enumerations**: Comments often say "this enumeration might be incomplete" allowing interviewers to add new values

5. **No Direct Questions**: Many comments explicitly say "don't ask the interviewees" - information should be inferred from conversation

6. **Example Data**: Complex schemas like ORM include example data structures to validate understanding

### Implications for Implementation

1. **DS Loader** needs to handle both simple and complex nested structures
2. **Combine logic** will need to respect the `-id` fields for upserts
3. **Interviewer tools** need to parse the comments for instructions
4. **Validation** should check for required properties and valid enumerations
5. **Completion** logic varies by schema - some use `exhausted?`, others have custom rules

### Next Steps

With this understanding, we can:
- Build interviewer tools that properly interpret DS structures
- Implement combine logic that handles different property patterns
- Create validation for DS-specific constraints
- Design the orchestrator to choose appropriate schemas based on conversation state