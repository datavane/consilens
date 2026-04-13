package com.consilens.ai.chat;

import com.consilens.ai.model.Intent;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parses user messages to determine their primary intent using keyword heuristics.
 */
@Slf4j
public class IntentParser {

    private static final List<String> DIFF_KEYWORDS = Arrays.asList(
            "diff", "compare", "比较", "对比", "check", "verify", "sync check"
    );
    private static final List<String> EXPLAIN_KEYWORDS = Arrays.asList(
            "explain", "why", "what", "告诉我", "解释", "分析", "analyze", "analyse", "reason", "cause"
    );
    private static final List<String> REPAIR_KEYWORDS = Arrays.asList(
            "repair", "fix", "修复", "修正", "sql", "generate sql", "patch", "correct"
    );
    private static final List<String> SCHEMA_KEYWORDS = Arrays.asList(
            "schema", "structure", "columns", "fields", "表结构", "discover", "show table"
    );
    private static final List<String> CONFIG_KEYWORDS = Arrays.asList(
            "config", "configuration", "yaml", "yml", "generate config", "配置"
    );
    private static final List<String> HELP_KEYWORDS = Arrays.asList(
            "help", "帮助", "how to", "what can", "usage", "commands"
    );

    /**
     * Determines the primary intent from the given user message.
     *
     * @param message the user's input
     * @return the detected intent
     */
    public Intent parse(String message) {
        if (message == null || message.isBlank()) {
            return Intent.UNKNOWN;
        }
        String lower = message.toLowerCase(Locale.ROOT);

        if (containsAny(lower, HELP_KEYWORDS)) return Intent.SHOW_HELP;
        if (containsAny(lower, DIFF_KEYWORDS)) return Intent.DIFF_TABLE;
        if (containsAny(lower, REPAIR_KEYWORDS)) return Intent.SUGGEST_REPAIR;
        if (containsAny(lower, EXPLAIN_KEYWORDS)) return Intent.EXPLAIN_RESULT;
        if (containsAny(lower, SCHEMA_KEYWORDS)) return Intent.DISCOVER_SCHEMA;
        if (containsAny(lower, CONFIG_KEYWORDS)) return Intent.GENERATE_CONFIG;

        return Intent.GENERAL_CHAT;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
