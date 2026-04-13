package com.consilens.ai.tool;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Map;

/**
 * Represents the result of a tool execution.
 */
@Data
@Builder
public class ToolResult {

    /** Whether the tool executed successfully. */
    private boolean success;

    /** Human-readable content of the result. */
    private String content;

    /** Optional structured data for programmatic use. */
    private Object structuredData;

    /** Error message if the tool failed. */
    private String error;

    public static ToolResult success(String content) {
        return ToolResult.builder().success(true).content(content).build();
    }

    public static ToolResult success(String content, Object structuredData) {
        return ToolResult.builder().success(true).content(content).structuredData(structuredData).build();
    }

    public static ToolResult failure(String error) {
        return ToolResult.builder().success(false).error(error).content("Error: " + error).build();
    }
}
