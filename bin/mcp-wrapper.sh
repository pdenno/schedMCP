#!/bin/bash
# Wrapper script for schedMCP to ensure proper environment

# Set HOME if not already set
export HOME="${HOME:-/home/pdenno}"

# Set up Clojure cache directory
export CLJ_CACHE="${HOME}/.clojure/.cpcache"

# Ensure SCHEDULING_TBD_DB is set
export SCHEDULING_TBD_DB="${SCHEDULING_TBD_DB:-/opt/scheduling}"

# Create necessary directories
mkdir -p "$HOME/.clojure"
mkdir -p "$SCHEDULING_TBD_DB/projects"
mkdir -p "$SCHEDULING_TBD_DB/system"

# Change to the project directory
cd "$(dirname "$0")/.."

# Start the MCP server
exec clojure -M:dev -m sched-mcp.main
