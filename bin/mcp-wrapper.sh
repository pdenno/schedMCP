#!/bin/bash
# Wrapper script for schedMCP to ensure proper environment

# Set HOME if not already set
export HOME="${HOME:-/home/pdenno}"

# Set up Clojure cache directory
export CLJ_CACHE="${HOME}/.clojure/.cpcache"

# Ensure SCHEDULING_TBD_DB is set
export SCHED_MCP_DB="${SCHED_MCP_DB:-/home/pdenno/Documents/git/schedMCP/test/dbs}"

# Create necessary directories
mkdir -p "$HOME/.clojure"
mkdir -p "$SCHED_MCP_DB/projects"
mkdir -p "$SCHED_MCP_DB/system"

# Change to the project directory
cd "$(dirname "$0")/.."

# Start the MCP server
exec clojure -M:dev -m sched-mcp.main
