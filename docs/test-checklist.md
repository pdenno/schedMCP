# schedMCP Test Checklist

## Pre-DS Implementation Testing

### Basic Interview Flow ✓
- [x] Start interview creates project and conversation
- [x] Get context returns current state
- [x] Submit answer accepts responses
- [x] Get answers returns collected data
- [x] Null pointer errors fixed
- [x] Question progression works

### State Persistence
- [x] Project database created
- [x] Conversations stored
- [x] Messages recorded
- [ ] EADS data properly updated (has issues)
- [ ] Question IDs properly tracked

### Database Operations
- [x] Connect to project DB
- [x] Query conversations
- [x] Store EADS structures
- [ ] Handle concurrent access
- [ ] Clean shutdown

### MCP Response Format
- [x] Proper JSON structure
- [x] Error handling
- [x] Success indicators
- [ ] Consistent field naming
- [ ] Complete progress info

## DS Implementation Requirements

### DS Loading
- [x] Load JSON files
- [x] Parse DS structure
- [x] List available DS
- [ ] Load matching .clj files
- [ ] Cache loaded DS

### Interviewer Tools (Upcoming)
- [ ] `interviewer_formulates_question`
  - [ ] Accepts DS + ASCR + context
  - [ ] Parses DS comments for guidance
  - [ ] Generates contextual questions
- [ ] `interviewer_interprets_response` 
  - [ ] Maps NL to DS fields
  - [ ] Creates valid SCR
  - [ ] Handles ambiguity

### Orchestrator Tools (Upcoming)
- [ ] `get_next_ds`
  - [ ] Follows DS sequencing rules
  - [ ] Considers conversation context
  - [ ] Respects prerequisites
- [ ] `start_ds_pursuit`
  - [ ] Initializes DS state
  - [ ] Sets up budget
  - [ ] Returns instructions

### State Management
- [ ] SCR storage with each answer
- [ ] ASCR aggregation logic
- [ ] DS-specific combine functions
- [ ] Completion detection

### Integration Tests
- [ ] Full warm-up → problem-type → flow-shop sequence
- [ ] Multi-DS interview flow
- [ ] State persistence across tool calls
- [ ] Proper SCR → ASCR aggregation

## Performance Considerations
- [ ] Tool response time < 2s
- [ ] DB query optimization
- [ ] Large ASCR handling
- [ ] Concurrent interviews

## Error Scenarios
- [ ] Missing project ID
- [ ] Invalid DS ID
- [ ] Malformed answers
- [ ] DB connection failures
- [ ] DS loading errors

## Documentation
- [x] Current state documented
- [x] Bug fixes documented
- [x] DS patterns documented
- [ ] Tool API documented
- [ ] Usage examples

---

**Ready for DS Implementation**: With bugs fixed, file structure ready, and understanding of DS patterns, we can now implement the core Discovery Schema functionality.