package com.consilens.ai.prompt;

import com.consilens.ai.chat.ConversationContext;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the system prompt for the AI assistant based on the current conversation context.
 */
public class SystemPromptBuilder {

    private static final String BASE_PROMPT =
            "You are Consilens AI, an intelligent data consistency assistant powered by the Consilens platform.\n"
                    + "You help users compare, analyze, and repair data inconsistencies between databases.\n\n"
                    + "Your capabilities:\n"
                    + "1. **consilens_diff** - Compare two database tables and find differences\n"
                    + "2. **consilens_analyze** - Analyze diff results to identify patterns and root causes\n"
                    + "3. **consilens_repair_generate** - Generate SQL to fix data inconsistencies\n"
                    + "4. **consilens_schema_discover** - Discover table schemas via JDBC\n"
                    + "5. **consilens_config_generate** - Generate Consilens YAML configuration files\n\n"
                    + "Guidelines:\n"
                    + "- Always use the available tools to gather real data before answering\n"
                    + "- When asked to compare tables, use the diff tool\n"
                    + "- When asked to explain differences, use the analyze tool\n"
                    + "- Provide concrete, actionable advice\n"
                    + "- Respond in the same language the user uses\n";

    /**
     * Builds a system prompt incorporating session context.
     *
     * @param context the current conversation context
     * @return the assembled system prompt
     */
    public String build(ConversationContext context) {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);

        sb.append("\n## Session Context\n");
        sb.append("Current time: ").append(ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");

        if (!context.getConnections().isEmpty()) {
            sb.append("\n### Known Connections\n");
            context.getConnections().forEach((alias, info) ->
                    sb.append("- **").append(alias).append("**: ").append(info.getType())
                            .append(" @ ").append(info.getHost() != null ? info.getHost() : info.getUrl()).append("\n")
            );
        }

        if (context.getLatestResultId() != null) {
            sb.append("\n### Latest Diff Result\n");
            sb.append("Result ID: `").append(context.getLatestResultId()).append("` is available for analysis.\n");
        }

        return sb.toString();
    }
}
