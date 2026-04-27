# Consilens AI Module

Consilens AI is an intelligent data consistency assistant that combines natural language processing with database comparison and repair capabilities.

## Features

- 🤖 **Natural Language Interface**: Understand your data consistency queries in plain English (and other languages)
- 🔍 **Smart Diff Analysis**: Compare database tables and identify inconsistencies with pattern detection
- 🛠️ **Automated Repair**: Generate SQL statements to fix data inconsistencies
- 🔌 **Pluggable Architecture**: Extensible tool system and LLM backend support
- 🧠 **Pattern Detection**: Identify common root causes like timezone mismatches, encoding issues, and data truncation
- 📊 **Schema Discovery**: Automatically discover and document database table schemas
- 🔒 **Security-First Design**: Input validation, password protection, and audit-friendly operations

## Quick Start

### Prerequisites

- Java 11+
- Maven 3.6+
- Optional: Ollama for local LLM support

### Building

```bash
mvn clean install -pl consilens-ai -am
```

### Basic Usage

```java
// Initialize components
SessionContext session = SessionContext.builder()
    .conversation(new ConversationContext())
    .backend(new OllamaBackend("http://localhost:11434"))
    .analyzer(new RuleBasedAnalyzer())
    .build();

// Set up tools
ToolRegistry tools = new ToolRegistry();
tools.register(new DiffTool());
tools.register(new AnalyzeTool());
tools.register(new RepairGenerateTool());
tools.register(new SchemaDiscoveryTool());
tools.register(new ConfigGenerateTool());

// Create chat engine
ChatEngine engine = new ChatEngine(
    session.getBackend(),
    tools,
    session.getAnalyzer()
);

// Have a conversation
String response = engine.chat(
    "Compare my production and staging users table",
    session.getConversation()
);
System.out.println(response);
```

## Module Structure

```
consilens-ai/
├── consilens-ai-core/          # Chat engine, intent parsing, system prompts
├── consilens-ai-analyzer/      # Pattern detection engine
│   ├── consilens-ai-analyzer-api/
│   └── consilens-ai-analyzer-plugins/consilens-ai-analyzer-rulebased/
├── consilens-ai-llm/           # LLM backend support
│   ├── consilens-ai-llm-api/
│   └── consilens-ai-llm-plugins/
│       ├── consilens-ai-llm-noop/
│       └── consilens-ai-llm-ollama/
└── consilens-ai-tool/          # Tool system
    ├── consilens-ai-tool-api/
    └── consilens-ai-tool-plugins/consilens-ai-tool-defaults/
```

## Available Tools

### DiffTool
Compares two database tables via JDBC and identifies all differences.

**Example**: "Compare the orders table between production and staging"

### AnalyzeTool
Analyzes a diff result to identify patterns, root causes, and repair suggestions.

**Example**: "What patterns do you see in the differences?"

### RepairGenerateTool
Generates SQL statements to fix data inconsistencies.

**Example**: "Generate SQL to fix these mismatches on the target side"

### SchemaDiscoveryTool
Discovers table schemas from JDBC connections.

**Example**: "Show me the schema of the users table"

### ConfigGenerateTool
Generates Consilens YAML configuration files for table comparisons.

**Example**: "Generate a config for comparing these tables"

## Pattern Detection

The built-in rule-based analyzer detects:

- **EncodingPattern**: Character encoding mismatches
- **TimeWindowPattern**: Time range filtering issues
- **TimeDriftPattern**: Timezone and time synchronization problems
- **NullHandlingPattern**: Null vs empty string differences
- **PrecisionLossPattern**: Floating-point and decimal precision issues
- **TruncationPattern**: String or numeric truncation

## Configuration

### LLM Backend

Configure Ollama (local LLM):
```java
LLMBackend backend = new OllamaBackend("http://localhost:11434");
```

Or use no-op backend (fallback to rule-based):
```java
LLMBackend backend = new NoopBackend();
```

### Connection Management

```java
ConversationContext context = new ConversationContext();
context.registerConnection("prod", 
    ConnectionInfo.builder()
        .type("mysql")
        .url("jdbc:mysql://prod-server:3306/db")
        .username("user")
        .password("password")
        .build());
```

## Security

- **Input Validation**: All user inputs are sanitized to prevent prompt injection
- **Password Protection**: Credentials are marked transient and never logged
- **SQL Safety**: Generated SQL is presented for review before execution
- **Audit Trail**: Tool operations are logged for compliance

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed security considerations.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and design decisions
- [USAGE.md](USAGE.md) - Detailed usage guide with examples
- [API Documentation](../docs/) - Complete API reference

## Testing

Run tests with:
```bash
mvn test -pl consilens-ai -am
```

Test coverage includes:
- Intent parsing (multilingual)
- Tool execution and error handling
- LLM integration with mocked backends
- Configuration generation
- Schema discovery

## Contributing

To add a custom tool:

1. Implement the `Tool` interface
2. Register via `ToolRegistry.register(tool)`
3. Update system prompt in `SystemPromptBuilder` to mention the new tool

To add a new analyzer:

1. Implement the `AIAnalyzer` interface
2. Register via ServiceLoader in `META-INF/services/`

To add a new LLM backend:

1. Implement the `LLMBackend` interface
2. Register via ServiceLoader in `META-INF/services/`

## Performance Considerations

- Tool execution is synchronous; long-running operations block the conversation
- Set reasonable limits on database queries to avoid memory issues
- Consider connection pooling for repeated database access
- Conversation history grows with interaction count

## Troubleshooting

### No response or "No LLM backend is configured"
- Ensure Ollama is running: `ollama serve`
- Verify OllamaBackend URL configuration
- Use NoopBackend if LLM is not available

### Tool execution errors
- Check JDBC URLs, credentials, and firewall settings
- Verify database user has required permissions
- Review tool input schema for required parameters

### Input too long
- Messages are truncated to 10,000 characters
- Break long requests into multiple queries

## License

See [LICENSE](../../LICENSE) file in the root directory.

## Support

For issues, questions, or contributions, see the main repository: https://github.com/NoeticLens/consilens
