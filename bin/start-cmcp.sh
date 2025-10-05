#!/bin/bash
LOG_FILE="/home/pdenno/Documents/git/schedMCP/logs/cmcp.log"

echo "$(date): Starting clojure-mcp with PID $$" >> "$LOG_FILE"

cd /home/pdenno/Documents/git/schedMCP
#exec clojure -X:mcp :start-nrepl-cmd '["clojure" "-M:nrepl"]' :port 7888
exec clojure -X:mcp :port 7888
