package com.consilens.ai.tool;

import com.consilens.ai.model.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI interface representing a tool that the AI agent can invoke.
 */
public interface Tool {

    /**
     * Returns the unique name of this tool (used by the LLM for function calling).
     */
    String getName();

    /**
     * Returns a human-readable description of what this tool does.
     */
    String getDescription();

    /**
     * Returns a JSON Schema node describing the tool's input parameters.
     */
    JsonNode getInputSchema();

    /**
     * Returns {@code true} if this tool only reads data and never modifies it.
     */
    boolean isReadOnly();

    /**
     * Executes this tool with the given input and context.
     *
     * @param input   the tool's input parameters as a JSON object
     * @param context the current tool execution context
     * @return the result of the tool execution
     */
    ToolResult execute(JsonNode input, ToolContext context);

    /**
     * Returns {@code true} if this tool is currently enabled and available.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Converts this tool to a {@link FunctionDefinition} for use with LLM backends.
     */
    default FunctionDefinition toFunctionDefinition() {
        return FunctionDefinition.builder()
                .name(getName())
                .description(getDescription())
                .parameters(getInputSchema())
                .build();
    }
}
