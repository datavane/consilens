package com.consilens.ai.tool;

import com.consilens.ai.model.FunctionDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry that holds all available tools for an AI session.
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Registers a tool. Overwrites any existing tool with the same name.
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {}", tool.getName());
    }

    /**
     * Finds a tool by name.
     */
    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Returns all registered tools.
     */
    public List<Tool> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    /**
     * Returns only enabled tools.
     */
    public List<Tool> getEnabled() {
        return tools.values().stream()
                .filter(Tool::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * Converts all enabled tools to {@link FunctionDefinition} objects for use with LLM backends.
     */
    public List<FunctionDefinition> toFunctionDefinitions() {
        return getEnabled().stream()
                .map(Tool::toFunctionDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Executes a tool by name with the given input and context.
     *
     * @param name    the tool name
     * @param input   the tool input as a JSON node
     * @param context the tool execution context
     * @return the tool result
     */
    public ToolResult executeTool(String name, JsonNode input, ToolContext context) {
        Optional<Tool> tool = findByName(name);
        if (!tool.isPresent()) {
            return ToolResult.failure("Unknown tool: " + name);
        }
        if (!tool.get().isEnabled()) {
            return ToolResult.failure("Tool is disabled: " + name);
        }
        try {
            return tool.get().execute(input, context);
        } catch (Exception e) {
            log.error("Tool execution failed for {}: {}", name, e.getMessage(), e);
            return ToolResult.failure("Tool execution error: " + e.getMessage());
        }
    }
}
