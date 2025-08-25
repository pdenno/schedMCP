#!/bin/bash
# I'm not using this it typical operation! I think we can keep stdout clean without it.
# Keep it around in case you have problems.
# MCP wrapper script for schedMCP - ensures clean stdout for JSON-RPC

# Set environment variables
export HOME="${HOME:-/home/pdenno}"
export SCHEDULING_TBD_DB="${SCHEDULING_TBD_DB:-/opt/scheduling}"

# Create directories silently (redirect to stderr)
mkdir -p "$HOME/.clojure" 2>&1
mkdir -p "$SCHEDULING_TBD_DB/projects" 2>&1
mkdir -p "$SCHEDULING_TBD_DB/system" 2>&1
mkdir -p "$(dirname "$0")/../logs" 2>&1

# Change to project directory
cd "$(dirname "$0")/.." 2>&1

# Start the MCP server
# All output except JSON-RPC must go to stderr or files
exec clojure -M -m sched-mcp.main 2>>logs/mcp-stderr.log
