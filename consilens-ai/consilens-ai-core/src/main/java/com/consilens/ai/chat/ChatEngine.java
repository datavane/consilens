package com.consilens.ai.chat;

import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.Intent;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.prompt.SystemPromptBuilder;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.tool.ToolContext;
import com.consilens.ai.tool.ToolRegistry;
import com.consilens.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Core AI chat engine that orchestrates LLM calls, tool invocations, and rule-based fallback.
 */
@Slf4j
public class ChatEngine {

    private static final int MAX_TOOL_CALL_TURNS = 20;

    private final LLMBackend backend;
    private final ToolRegistry toolRegistry;
    private final AIAnalyzer analyzer;
    private final SystemPromptBuilder promptBuilder;
    private final IntentParser intentParser;
    private final ObjectMapper objectMapper;

    public ChatEngine(LLMBackend backend, ToolRegistry toolRegistry, AIAnalyzer analyzer) {
        this.backend = backend;
        this.toolRegistry = toolRegistry;
        this.analyzer = analyzer;
        this.promptBuilder = new SystemPromptBuilder();
        this.intentParser = new IntentParser();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Processes a user message and returns the assistant's response.
     *
     * @param userMessage the user's natural language input
     * @param context     the active conversation context
     * @return the assistant's response text
     */
    public String chat(String userMessage, ConversationContext context) {
        context.addMessage(ChatMessage.user(userMessage));

        // If LLM is not available, use rule-based fallback
        if (!backend.isAvailable()) {
            return handleWithRuleFallback(userMessage, context);
        }

        return handleWithLLM(userMessage, context);
    }

    private String handleWithLLM(String userMessage, ConversationContext context) {
        String systemPrompt = promptBuilder.build(context);
        List<FunctionDefinition> tools = toolRegistry.toFunctionDefinitions();
        ToolContext toolContext = ToolContext.builder()
                .conversation(context)
                .analyzer(analyzer)
                .build();

        List<ChatMessage> history = context.getHistory();
        // Remove the system message from history (it's passed separately)
        history.removeIf(m -> m.getRole() == ChatMessage.Role.SYSTEM);

        int turns = 0;
        while (turns < MAX_TOOL_CALL_TURNS) {
            turns++;
            LLMResponse response = backend.chat(systemPrompt, history, tools);

            if (response.hasToolCalls()) {
                // Add assistant's tool call message
                ChatMessage assistantMsg = ChatMessage.assistantWithToolCalls(
                        response.getText(), response.getToolCalls());
                context.addMessage(assistantMsg);
                history.add(assistantMsg);

                // Execute each tool call and add results
                for (ChatMessage.ToolCall toolCall : response.getToolCalls()) {
                    ToolResult result = executeTool(toolCall, toolContext);
                    ChatMessage toolResultMsg = ChatMessage.toolResult(toolCall.getId(), result.getContent());
                    context.addMessage(toolResultMsg);
                    history.add(toolResultMsg);
                }
                // Continue the loop to let LLM process tool results
                continue;
            }

            // Final text response
            String assistantText = response.hasTextContent() ? response.getText() : "(No response)";
            context.addMessage(ChatMessage.assistant(assistantText));
            return assistantText;
        }

        String fallback = "Reached maximum tool call limit. Please try rephrasing your request.";
        context.addMessage(ChatMessage.assistant(fallback));
        return fallback;
    }

    private ToolResult executeTool(ChatMessage.ToolCall toolCall, ToolContext toolContext) {
        try {
            JsonNode input = objectMapper.valueToTree(toolCall.getArguments());
            return toolRegistry.executeTool(toolCall.getName(), input, toolContext);
        } catch (Exception e) {
            log.error("Failed to execute tool {}: {}", toolCall.getName(), e.getMessage(), e);
            return ToolResult.failure("Tool execution error: " + e.getMessage());
        }
    }

    private String handleWithRuleFallback(String userMessage, ConversationContext context) {
        Intent intent = intentParser.parse(userMessage);
        log.debug("Rule fallback intent: {}", intent);

        String response;
        switch (intent) {
            case SHOW_HELP:
                response = buildHelpText();
                break;
            case DIFF_TABLE:
                response = "To compare tables, please provide the JDBC URLs, credentials, and table names. "
                        + "Example: 'Compare jdbc:mysql://source/db.orders with jdbc:mysql://target/db.orders on primary key id'";
                break;
            case EXPLAIN_RESULT:
                if (context.getLatestDiffResult().isPresent()) {
                    response = analyzer.explainResult(context.getLatestDiffResult().get());
                } else {
                    response = "No diff result available yet. Please run a diff first.";
                }
                break;
            case SUGGEST_REPAIR:
                if (context.getLatestDiffResult().isPresent()) {
                    response = "To generate repair SQL, I need a table name. Please provide it.";
                } else {
                    response = "No diff result available yet. Please run a diff first.";
                }
                break;
            case GENERATE_CONFIG:
                response = "To generate a config, please provide: source URL, source table, target URL, target table, and primary keys.";
                break;
            default:
                response = "No LLM backend is configured. I can still help with rule-based analysis. "
                        + "Type 'help' to see available commands.";
                break;
        }

        context.addMessage(ChatMessage.assistant(response));
        return response;
    }

    private String buildHelpText() {
        return "## Consilens AI Help\n\n"
                + "Available commands:\n"
                + "- **diff** - Compare two database tables\n"
                + "- **analyze** - Analyze a diff result\n"
                + "- **repair** - Generate repair SQL\n"
                + "- **schema** - Discover table schema\n"
                + "- **config** - Generate configuration file\n\n"
                + "Example: 'Compare the orders table between production and staging databases'\n";
    }
}
