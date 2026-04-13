package com.consilens.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * Defines a function/tool that can be called by the LLM.
 */
@Data
@Builder
public class FunctionDefinition {

    /** Unique name of the function. */
    private String name;

    /** Human-readable description for the LLM to understand when to call this function. */
    private String description;

    /** JSON Schema describing the function's input parameters. */
    private JsonNode parameters;
}
