# Quick Start Guide for schedMCP

## Prerequisites

1. Set up environment variable:
   ```bash
   export SCHEDULING_TBD_DB=/opt/scheduling
   ```

2. Create database directories:
   ```bash
   mkdir -p $SCHEDULING_TBD_DB/projects
   mkdir -p $SCHEDULING_TBD_DB/system
   ```

## Starting the Server

### Option 1: Direct Start (for Claude Desktop)
```bash
cd /path/to/schedMCP
clojure -M -m sched-mcp.main
```

### Option 2: Development REPL
```bash
cd /path/to/schedMCP
clojure -M:dev

# In the REPL:
(start)  ; Starts mount components
```

## Configure Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "schedmcp": {
      "command": "clojure",
      "args": ["-M", "-m", "sched-mcp.main"],
      "cwd": "/absolute/path/to/schedMCP",
      "env": {
        "SCHEDULING_TBD_DB": "/opt/scheduling"
      }
    }
  }
}
```

## Testing the Integration

1. Restart Claude Desktop after updating the configuration
2. In a new Claude conversation, try:
   - "I need help scheduling my craft beer brewery production"
   - Claude should use the `start_interview` tool and begin asking questions

## What to Expect

The interview will proceed through these questions:
1. **Scheduling challenges**: What bottlenecks or constraints do you face?
2. **Products/services**: What does your organization produce?
3. **Additional context**: Any other important information?

## Troubleshooting

### Server won't start
- Check that `SCHEDULING_TBD_DB` is set
- Ensure you have write permissions to the database directory
- Look for error messages in the terminal

### Claude can't connect
- Verify the path in claude_desktop_config.json is absolute
- Check that the server is running (you should see log output)
- Try restarting Claude Desktop

### Interview state issues
- Each project gets its own database
- Projects are identified by name (spaces become hyphens)
- Data persists between server restarts

## Example Conversation Flow

**You**: "I have a brewery that needs help with production scheduling"

**Claude**: [Uses start_interview tool] "I'll help you with scheduling for your brewery. Let me start by understanding your challenges. What are the main scheduling challenges you face in your manufacturing process?"

**You**: "We have 3 brewing tanks but 5 different beer types to produce, and each has different fermentation times"

**Claude**: [Uses submit_answer tool] "Thank you. Now, could you tell me what products your brewery produces?"

**You**: "We make IPAs, stouts, lagers, wheat beers, and seasonal specials"

[Interview continues...]
