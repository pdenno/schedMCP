#!/bin/bash
LOG_FILE="/home/pdenno/Documents/git/schedMCP/logs/wrapper.log"

echo "$(date): Starting schedMCP with PID $$" >> "$LOG_FILE"

cd /home/pdenno/Documents/git/schedMCP
exec clojure -M:nrepl -m sched-mcp.main 2>> "$LOG_FILE"
