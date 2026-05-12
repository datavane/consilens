# Consilens AI Module Usage Guide

## Quick Start

### Production CLI Flow

```bash
consilens ai config "compare users from mysql to postgresql by id" \
  --no-llm \
  --source-type mysql \
  --source-url jdbc:mysql://localhost:3306/mydb \
  --source-table users \
  --source-user-env MYSQL_USER \
  --source-password-env MYSQL_PASSWORD \
  --target-type postgresql \
  --target-url jdbc:postgresql://localhost:5432/mydb \
  --target-table users \
  --target-user-env PG_USER \
  --target-password-env PG_PASSWORD \
  --keys id \
  --fields name,email,status \
  --output diff.yaml

consilens ai explain -c diff.yaml
consilens diff --dry-run -c diff.yaml
consilens diff -c diff.yaml
consilens ai diagnose --result diff-records.json --analyzer rulebased --output diagnose.md
consilens ai providers
consilens ai providers --format json
consilens ai doctor --format json
```

For cloud LLMs, set `--backend openai` with `OPENAI_API_KEY`, or `--backend deepseek` with `DEEPSEEK_API_KEY`. `CONSILENS_AI_BACKEND`, `CONSILENS_AI_MODEL`, `CONSILENS_AI_BASE_URL` and `CONSILENS_AI_TIMEOUT` can provide environment defaults. The AI command produces structured configuration; real diff execution still goes through the existing deterministic engine.

`ai diagnose` requires diff evidence, not only summary statistics. Configure a `json` `diff-record` sink before running `consilens diff`:
The analyzer is selected via SPI with `--analyzer <name>` or `CONSILENS_AI_ANALYZER`; default: `rulebased`.
Use `--output` to write the report to a file; omit it to print to stdout.
Use `ai providers` to verify discovered analyzer and LLM backend providers before enabling a production task. Add `--format json` for CI checks and scripts.
Use `ai doctor` for production preflight checks. It verifies provider discovery, selected analyzer/backend creation and required API key configuration without network calls by default; add `--online` to verify backend reachability.

```yaml
result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff-records.json
        pretty: true
```

### Basic Conversation

```java
// Create a session context
SessionContext session = SessionContext.builder()
    .conversation(new ConversationContext())
    .backend(new OllamaBackend("http://localhost:11434"))
    .analyzer(new RuleBasedAnalyzer())
    .build();

// Create the chat engine
ToolRegistry toolRegistry = new ToolRegistry();
toolRegistry.register(new DiffTool());
toolRegistry.register(new AnalyzeTool());
toolRegistry.register(new SchemaDiscoveryTool());

ChatEngine engine = new ChatEngine(
    session.getBackend(),
    toolRegistry,
    session.getAnalyzer()
);

// Have a conversation
String response = engine.chat(
    "Compare my production and staging databases", 
    session.getConversation()
);
System.out.println(response);
```

Cloud backend examples:

```java
LLMBackend openai = new OpenAIBackend(
    "https://api.openai.com/v1",
    "gpt-4.1-mini",
    System.getenv("OPENAI_API_KEY")
);

LLMBackend deepseek = new DeepSeekBackend(
    "https://api.deepseek.com",
    "deepseek-chat",
    System.getenv("DEEPSEEK_API_KEY")
);
```

## Common Use Cases

### 1. Compare Two Database Tables

```
User: "Compare the 'users' table between production and staging"

SDK/demo response: The AI will:
1. Ask for connection details (URLs, credentials)
2. Execute the diff using DiffTool
3. Report the number of differences found
4. Store the diff result for further analysis
```

For production CLI usage, prefer `consilens ai config` followed by `consilens diff --dry-run` and `consilens diff`.

**Tool Input Schema** (DiffTool):
```json
{
  "source_url": "jdbc:mysql://prod-server:3306/db",
  "source_username": "user",
  "source_password": "password",
  "source_table": "public.users",
  "target_url": "jdbc:mysql://staging-server:3306/db",
  "target_username": "user",
  "target_password": "password",
  "target_table": "public.users",
  "primary_keys": "id",
  "limit": 10000
}
```

### 2. Analyze Diff Results

```
User: "What patterns do you see in the differences?"

Response: The AI will:
1. Use the latest diff result
2. Run pattern analysis (AnalyzeTool)
3. Identify root causes:
   - Encoding mismatches
   - Timezone or time drift issues
   - Null handling differences
   - Data truncation
4. Provide explanations and recommendations
```

Production CLI:

```bash
consilens ai diagnose --result diff-records.json --analyzer rulebased --output diagnose.md
```

The input must be either a JSON array of diff records or an object containing a `differences` array. A stats-only `result` JSON file is rejected because it does not contain row-level evidence.

### 3. Generate Repair SQL

```
User: "Generate SQL to fix these differences on the target side"

Response: The AI will:
1. Load the diff result
2. Generate appropriate SQL statements:
   - INSERT for missing rows
   - UPDATE for mismatched data
   - DELETE for extra rows
3. Present the SQL for review before executing
```

**Important**: Always review generated SQL before executing in production!

### 4. Discover Table Schema

```
User: "Show me the schema of the 'orders' table"

Response: The AI will:
1. Connect to the specified database
2. Fetch column definitions, types, sizes
3. Identify primary and foreign keys
4. Present a formatted table with the schema
```

**Tool Input Schema** (SchemaDiscoveryTool):
```json
{
  "url": "jdbc:mysql://localhost:3306/db",
  "username": "user",
  "password": "password",
  "schema": "public",
  "table": "orders"
}
```

### 5. Generate Configuration

```
User: "Generate a Consilens config for comparing these tables"

Response: The AI will:
1. Collect the database connection details
2. Generate a YAML configuration template
3. Present it for customization and use
```

## Advanced Features

### Multi-Turn Conversations

The AI maintains conversation context across multiple turns:

```
User (Turn 1): "Compare db1.users with db2.users on id"
AI: [Runs diff, stores result]

User (Turn 2): "What caused these mismatches?"
AI: [Uses stored result from Turn 1 to analyze]

User (Turn 3): "Generate repair SQL"
AI: [Uses the same result to generate SQL]
```

### Connection Caching

Register connections to avoid re-entering credentials:

```java
ConversationContext context = new ConversationContext();
context.registerConnection("prod", ConnectionInfo.builder()
    .type("mysql")
    .url("jdbc:mysql://prod-server:3306/db")
    .username("user")
    .password("password")
    .build());

// Later, the AI can reference this connection
String response = engine.chat(
    "Compare the users table in 'prod' connection with staging",
    context
);
```

### Custom Tool Integration

Implement the `Tool` interface to add custom capabilities:

```java
public class MyReportTool implements Tool {
    @Override
    public String getName() {
        return "generate_report";
    }
    
    @Override
    public String getDescription() {
        return "Generate a difference report in custom format";
    }
    
    @Override
    public JsonNode getInputSchema() {
        // Define input parameters
    }
    
    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        DiffResult diff = context.getConversation()
            .getLatestDiffResult()
            .orElse(null);
        
        if (diff == null) {
            return ToolResult.failure("No diff result found");
        }
        
        // Generate report
        String report = generateReport(diff);
        return ToolResult.success(report);
    }
    
    @Override
    public boolean isReadOnly() {
        return true;
    }
}
```

### Fallback Mode (No LLM)

If an LLM backend is not available, the system falls back to rule-based responses:

```java
ChatEngine engine = new ChatEngine(
    new NoopBackend(),  // No actual LLM
    toolRegistry,
    analyzer
);

// Still works, but with predefined responses
String response = engine.chat("Compare tables", context);
// Returns: "To compare tables, provide JDBC URLs, credentials, and table names..."
```

## Error Handling

### Tool Execution Failures

The AI gracefully handles tool failures:

```
User: "Compare with invalid connection"

Response (if connection fails):
"Tool execution error: Unable to connect to the database. 
Please verify the URL, username, and password."
```

### LLM Failures

Automatic retry with exponential backoff:
- First attempt: Immediate
- Retry 1: After 1 second
- Retry 2: After 2 seconds
- After 3 failures: Returns user-friendly error

### Input Validation

The system validates and sanitizes all inputs:
- Truncates messages longer than 10,000 characters
- Removes code fence markers to prevent prompt injection
- Filters null bytes
- Validates required parameters in tool schemas

## Performance Tips

1. **Set Reasonable Limits**: Use the `limit` parameter in DiffTool to fetch fewer rows:
   ```
   "limit": 1000  // Instead of default 10,000
   ```

2. **Use Schema Caching**: Cache schema information to avoid repeated JDBC introspection

3. **Batch Operations**: Group multiple comparisons into a single conversation rather than separate calls

4. **Monitor Tool Execution Time**: 
   - DiffTool is I/O bound (database queries)
   - Set timeouts for JDBC connections
   - Consider connection pooling for many tools

## Security Reminders

1. **Never Share Credentials**: Passwords are transient and never logged, but:
   - Don't share conversation logs containing database credentials
   - Use environment variables or secure vaults for passwords

2. **Review Generated SQL**: Always review and test repair SQL before executing:
   ```sql
   -- Review this before running in production!
   UPDATE users SET email = 'new@example.com' WHERE id = 123;
   ```

3. **Least Privilege**: Use database users with minimal required permissions:
   - DiffTool needs only SELECT
   - RepairGenerateTool only generates SQL (doesn't execute)
   - Execution is manual and auditable

4. **Audit Trail**: Enable database query logging to track:
   - When comparisons are performed
   - What tables are accessed
   - Who makes repairs

## Troubleshooting

### "No LLM backend is configured"
The system is running in fallback mode. Either:
- Start an Ollama server: `ollama serve`
- Configure an OllamaBackend with correct URL
- Set `OPENAI_API_KEY` and configure OpenAIBackend
- Set `DEEPSEEK_API_KEY` and configure DeepSeekBackend
- Or intentionally use NoopBackend for rule-based only

### "Unknown tool: consilens_diff"
The tool isn't registered. Ensure:
```java
toolRegistry.register(new DiffTool());
```

### "Tool execution error: java.sql.SQLException"
Database connection failed. Verify:
- JDBC URL format is correct
- Credentials are correct
- Firewall allows connection
- Database server is running

### "Input message truncated"
Your message was longer than 10,000 characters. Break it into smaller requests.

## Multilingual Support

The system supports multiple languages in user input:

```
English: "Compare the two tables"
Chinese: "比较这两个表"
Spanish: "Compara las dos tablas"
```

Intent parsing and generated responses adapt to the language used.
