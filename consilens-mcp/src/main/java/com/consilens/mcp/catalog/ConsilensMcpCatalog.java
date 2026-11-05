package com.consilens.mcp.catalog;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ConsilensMcpCatalog {

    private final Map<String, McpToolSpec> tools;

    public ConsilensMcpCatalog() {
        LinkedHashMap<String, McpToolSpec> definitions = new LinkedHashMap<>();
        put(definitions, tool("consilens.plan.config", "Plan config",
                "Build a compare config artifact from an explicit goal and endpoints.",
                List.of("goal", "source", "target", "keys"),
                props(
                        prop("goal", "string", "Compare goal."),
                        prop("source", "object", "Source endpoint with type/table/query."),
                        prop("target", "object", "Target endpoint with type/table/query."),
                        prop("keys", "array", "Primary key fields."),
                        prop("hints", "object", "Planner hints.")),
                "/v1/plan", false, true));
        put(definitions, tool("consilens.validate.config", "Validate config",
                "Validate a config artifact or inline config content.",
                List.of(),
                props(
                        prop("configArtifactId", "string", "CONFIG artifact id."),
                        prop("configContent", "object", "Inline config content."),
                        prop("options", "object", "Validation options.")),
                "/v1/validate", false, true));
        put(definitions, tool("consilens.run.diff", "Run diff",
                "Submit an asynchronous diff run. The caller must provide serialNo for idempotency.",
                List.of("serialNo", "configArtifactId"),
                props(
                        prop("serialNo", "string", "Caller-provided idempotency key."),
                        prop("configArtifactId", "string", "CONFIG artifact id."),
                        prop("options", "object", "Run options such as dryRun and timeoutMs.")),
                "/v1/tasks/execute", false, true));
        put(definitions, tool("consilens.diagnose.diff", "Diagnose diff",
                "Diagnose a run result artifact.",
                List.of("runArtifactId"),
                props(
                        prop("runArtifactId", "string", "RUN_RESULT artifact id."),
                        prop("options", "object", "Diagnosis options.")),
                "/v1/diagnose", false, true));
        put(definitions, tool("consilens.repair.config", "Repair config",
                "Generate a repair config from a diagnosis artifact.",
                List.of("diagnosisArtifactId"),
                props(
                        prop("diagnosisArtifactId", "string", "DIAGNOSIS artifact id."),
                        prop("options", "object", "Repair options.")),
                "/v1/repair", false, true));
        put(definitions, tool("consilens.get.artifact", "Get artifact",
                "Fetch artifact metadata and optional content.",
                List.of("artifactId"),
                props(
                        prop("artifactId", "string", "Artifact id."),
                        prop("includeContent", "boolean", "Whether to include artifact content.")),
                "/v1/artifacts/{artifactId}", true, true));
        this.tools = Map.copyOf(definitions);
    }

    public List<McpToolSpec> toolSpecs() {
        return List.copyOf(tools.values());
    }

    public List<McpSchema.Tool> tools() {
        return tools.values().stream().map(McpToolSpec::toTool).collect(Collectors.toList());
    }

    public Optional<McpToolSpec> findTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<McpSchema.ResourceTemplate> resourceTemplates() {
        return List.of(
                resourceTemplate("consilens://artifacts/{artifactId}", "Consilens artifact content"),
                resourceTemplate("consilens://configs/{configId}", "Consilens config content"),
                resourceTemplate("consilens://diagnosis/{diagnosisId}", "Consilens diagnosis content"));
    }

    public List<McpSchema.Prompt> prompts() {
        return List.of(
                prompt("compare-config-build", "Build and validate a compare config."),
                prompt("compare-closed-loop-run", "Validate config, run diff, and diagnose the result."),
                prompt("compare-diagnose-repair", "Diagnose a run result and generate repair config."),
                prompt("aggregate-compare-build", "Build and validate multiple compare configs."));
    }

    private void put(Map<String, McpToolSpec> target, McpToolSpec spec) {
        target.put(spec.name(), spec);
    }

    private McpToolSpec tool(String name,
                             String title,
                             String description,
                             List<String> required,
                             Map<String, Object> properties,
                             String httpPath,
                             boolean readOnly,
                             boolean idempotent) {
        return new McpToolSpec(name, title, description, required, properties, outputSchema(),
                new McpSchema.ToolAnnotations(title, readOnly, false, idempotent, true, false),
                readOnly ? "GET" : "POST", httpPath);
    }

    private McpSchema.ResourceTemplate resourceTemplate(String uriTemplate, String description) {
        return McpSchema.ResourceTemplate.builder()
                .uriTemplate(uriTemplate)
                .name(uriTemplate)
                .title(description)
                .description(description)
                .mimeType("application/json")
                .build();
    }

    private McpSchema.Prompt prompt(String name, String description) {
        return new McpSchema.Prompt(name, name, description, List.of());
    }

    private Map<String, Object> outputSchema() {
        return Map.of("type", "object", "additionalProperties", true);
    }

    @SafeVarargs
    private final Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private Map.Entry<String, Object> prop(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return Map.entry(name, property);
    }
}
