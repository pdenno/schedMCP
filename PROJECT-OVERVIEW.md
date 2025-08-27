# schedMCP Project Overview

## Project Purpose

schedMCP is a Model Context Protocol (MCP) implementation of schedulingTBD's Discovery Schema system for manufacturing scheduling. It uses human/machine teaming through conversational interviews to build customized scheduling systems.

## Key Concepts

### Discovery Schema (DS)
- **Definition**: Template data structures with instructions that guide interviewers to collect domain-specific scheduling information
- **Purpose**: Encode manufacturing scheduling expertise in a form that LLMs can use to conduct intelligent interviews
- **Note**: Previously called "EADS" (Example Annotated Data Structure) - this term is now deprecated

### Core Components
1. **Orchestrator** - Selects which Discovery Schema to pursue next based on conversation progress
2. **Interviewer** - Conducts interviews using Discovery Schemas to gather information
3. **Schema-Conforming Response (SCR)** - Structured data extracted from each user answer
4. **Aggregated SCR (ASCR)** - Combined knowledge from all SCRs for a given Discovery Schema

## Project Structure

### Living Documents (in `/docs/`)
These documents evolve as the project develops:
- `mcp-discovery-schema-design.md` - Current architecture and implementation plans
- `development-guidelines.md` - Coding standards and practices
- Other design documents as needed

### Source Directories
- `src/` - New MCP-based implementation (currently in early development)
- `examples/schedulingTBD/` - Original implementation for reference
- `examples/clojure-mcp/` - MCP patterns we borrow (but don't depend on in the sense of deps.edn)
- `resources/discovery-schemas/` - DS templates organized by domain

### Reference Implementation
The `examples/schedulingTBD/` directory contains the original system that we're reimplementing with MCP. Key locations:
- Discovery Schemas: `examples/schedulingTBD/resources/agents/iviewrs/EADS/`
- Domain implementations: `examples/schedulingTBD/src/server/scheduling_tbd/iviewr/domain/`
- Orchestrator logic: `examples/schedulingTBD/resources/agents/orchestrator.txt`

## Development Status

### Current Phase
Early implementation - fixing foundational bugs and establishing core architecture

### Completed
- Architecture design document
- Basic interview infrastructure
- Development guidelines

### In Progress
- Bug fixes for interview flow
- Discovery Schema loading system
- Tool implementation using MCP

### Upcoming
- Orchestrator tools
- Discovery Schema pursuit logic
- SCR/ASCR management
- Integration with existing DS templates

## Key Architectural Decisions

1. **MCP Tools are Stateless** - All state lives in the database; tools receive complete context
2. **Dual DS Format** - JSON for templates/instructions, Clojure for logic/validation
3. **Independent from clojure-mcp** - We borrow patterns but maintain no dependencies
4. **Flexible Orchestration** - Tools provide information, not prescriptions, preserving LLM reasoning

## For AI Assistants

When working on this project:
- Always check `/docs/` for the latest design decisions
- Refer to `CLAUDE.md` for specific AI coding instructions
- Use examples from `schedulingTBD` as authoritative patterns
- Borrow (don't reference) patterns from `clojure-mcp`

## Getting Started

1. Review the current state in `/docs/mcp-discovery-schema-design.md`
2. Check known issues and bugs in the design document
3. Look at Discovery Schema examples in `examples/schedulingTBD/`
4. Follow patterns in `examples/clojure-mcp/` for MCP tool implementation

This project is actively evolving. Always check the living documents in `/docs/` for the most current information.
