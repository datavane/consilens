package com.consilens.mcp.server;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

public class ConsilensPromptProvider {

    public McpSchema.GetPromptResult getPrompt(String name) {
        return switch (name) {
            case "compare-config-build" -> prompt(name,
                    "Use consilens.plan.config, then consilens.validate.config. Return the CONFIG artifact id and validation artifact id.");
            case "compare-closed-loop-run" -> prompt(name,
                    "Use consilens.validate.config, consilens.run.diff with caller-provided serialNo, then consilens.diagnose.diff when the run result artifact is available.");
            case "compare-diagnose-repair" -> prompt(name,
                    "Use consilens.diagnose.diff on the run result artifact, then consilens.repair.config on the diagnosis artifact.");
            case "aggregate-compare-build" -> prompt(name,
                    "For each compare target, use consilens.plan.config and consilens.validate.config. Return one config artifact per target.");
            default -> throw McpError.RESOURCE_NOT_FOUND.apply(name);
        };
    }

    private McpSchema.GetPromptResult prompt(String name, String text) {
        return new McpSchema.GetPromptResult(name, List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }
}
