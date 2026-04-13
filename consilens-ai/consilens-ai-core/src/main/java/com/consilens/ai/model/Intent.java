package com.consilens.ai.model;

/**
 * User intent categories parsed from natural language input.
 */
public enum Intent {
    /** User wants to compare two database tables. */
    DIFF_TABLE,

    /** User wants to understand a diff result. */
    EXPLAIN_RESULT,

    /** User wants repair SQL suggestions. */
    SUGGEST_REPAIR,

    /** User wants to discover database schema. */
    DISCOVER_SCHEMA,

    /** User wants to generate a config file. */
    GENERATE_CONFIG,

    /** User wants to see help information. */
    SHOW_HELP,

    /** General conversation, no specific Consilens action. */
    GENERAL_CHAT,

    /** Could not determine the user's intent. */
    UNKNOWN
}
