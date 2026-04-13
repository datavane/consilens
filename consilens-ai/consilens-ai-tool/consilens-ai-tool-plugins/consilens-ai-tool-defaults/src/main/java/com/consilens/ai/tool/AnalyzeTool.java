package com.consilens.ai.tool;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.model.PatternMatch;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tool that analyzes a diff result using the configured AI analyzer.
 */
@Slf4j
public class AnalyzeTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "consilens_analyze";
    }

    @Override
    public String getDescription() {
        return "Analyzes a diff result to identify patterns, root causes, and repair suggestions";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("result_id").put("type", "string")
                .put("description", "ID of a previously stored diff result");
        props.putObject("file_path").put("type", "string")
                .put("description", "Path to a JSON file containing the diff result");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        if (context.getAnalyzer() == null) {
            return ToolResult.failure("No AI analyzer is configured");
        }

        DiffResult diffResult = loadDiffResult(input, context);
        if (diffResult == null) {
            return ToolResult.failure("Could not load diff result. Provide either result_id or file_path.");
        }

        try {
            AnalysisResult analysis = context.getAnalyzer().analyze(diffResult);
            String explanation = context.getAnalyzer().explainResult(diffResult);
            return ToolResult.success(explanation, analysis);
        } catch (Exception e) {
            log.error("Analysis failed: {}", e.getMessage(), e);
            return ToolResult.failure("Analysis failed: " + e.getMessage());
        }
    }

    private DiffResult loadDiffResult(JsonNode input, ToolContext context) {
        String resultId = input.path("result_id").asText(null);
        if (resultId != null && !resultId.isEmpty()) {
            Optional<DiffResult> stored = context.getConversation().getDiffResult(resultId);
            if (stored.isPresent()) {
                return stored.get();
            }
        }

        // Try latest
        Optional<DiffResult> latest = context.getConversation().getLatestDiffResult();
        if (latest.isPresent()) {
            return latest.get();
        }

        // Try loading from file
        String filePath = input.path("file_path").asText(null);
        if (filePath != null && !filePath.isEmpty()) {
            try {
                return DiffResultLoader.fromFile(filePath);
            } catch (Exception e) {
                log.warn("Could not load diff result from file {}: {}", filePath, e.getMessage());
            }
        }

        return null;
    }
}
