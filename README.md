# DBeaver Copilot

An Eclipse plugin that embeds an agentic AI assistant directly inside DBeaver. Ask questions about your database in plain English and get correct SQL 

---

## How it works

DBeaver Copilot doesn't just wrap an LLM. It runs an iterative **think → explore → observe** loop that lets the AI figure out your schema on its own, step by step, before producing a final query.

### 1. You hit Send

`CopilotView` validates that there's an active connection, grabs the `DBCExecutionContext` from the open SQL editor, then launches a background Eclipse Job so the UI stays responsive. It calls `ChatService.send()` with the full chat history, the database type, and a callback for streaming UI updates.

### 2. The agentic loop runs

`ChatService` iterates up to **6 times**:

| Step | What happens |
|------|-------------|
| Call AI | `AIAssistant.generateText()` is called with the current message history |
| Parse response | The response is scanned for `<explore>SELECT ...</explore>` tags |
| Final answer? | If no `<explore>` block is found, the loop exits and the answer is returned |
| Execute query | The SELECT is run against the real database via `DBCExecutionContext` |
| Feed result back | The result is appended as a synthetic "user" message: `[Explore result]\n...` |
| Repeat | The AI sees the result and can dig deeper or produce the final query |

The AI receives **no pre-loaded schema**. Instead it discovers what it needs organically — querying `information_schema` for tables, then columns, then constructing the final SQL. It's required to verify column names before using them.

**Safety guardrails:**
- Only `SELECT` statements are permitted inside `<explore>` blocks
- Maximum of 6 loop iterations
- Explore results are truncated at 200 rows

### 3. You get your SQL

Once the AI responds with a ` ```sql ``` ` block instead of another `<explore>` tag, the loop exits. The view:

- Renders the full response in the chat area
- Shows an **Insert SQL into editor** button
- Re-enables the input for follow-up questions

---

## Architecture

```
CopilotView (UI / Eclipse ViewPart)
    └── ChatService (agentic loop)
            ├── AIAssistantRegistry → DBeaver's AI provider (Claude, etc.)
            └── DBCExecutionContext → real DB connection for explore queries
```

Two files, ~500 lines total.

---

## Requirements

- DBeaver 23.x or later (Community or Pro)
- An AI provider configured in DBeaver's AI Assistant settings (Claude, OpenAI, etc.)
- An active database connection in the SQL editor

---

## Installation

1. In DBeaver, go to **Help → Install New Software**
2. Click **Add** and enter the following URL:
   ```
   https://github.com/atyion/dbeavercopilot/
   ```
3. Follow the prompts and restart DBeaver

---

## Usage

1. Open a SQL editor with an active connection
2. Open the **Copilot** view: **Window → Show View → DBeaver Copilot**
3. Type your question in plain English, e.g.:

   > *"Show me the top 10 customers by total order value in the last 90 days"*

4. The plugin explores your schema automatically and returns a ready-to-run SQL query
5. Click **Insert SQL into editor** to paste it directly, or ask a follow-up question

---

## Configuration

DBeaver Copilot uses whichever AI provider is configured under **Window → Preferences → General → AI**. No additional setup is required.

---

## Contributing

Pull requests are welcome. For significant changes, please open an issue first to discuss what you'd like to change.

---

## License

[MIT](LICENSE)
