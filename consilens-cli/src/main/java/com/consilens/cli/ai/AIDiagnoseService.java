package com.consilens.cli.ai;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads diff evidence and runs deterministic analyzer diagnostics.
 */
public class AIDiagnoseService {

    private final ObjectMapper objectMapper;
    private final AIAnalyzer analyzer;

    public AIDiagnoseService(AIAnalyzer analyzer) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        this.analyzer = analyzer;
    }

    public String diagnose(String resultPath) throws IOException {
        DiffResult result = loadResult(resultPath);
        AnalysisResult analysis = analyzer.analyze(result);
        return format(result, analysis);
    }

    DiffResult loadResult(String resultPath) throws IOException {
        JsonNode root = objectMapper.readTree(new File(resultPath));
        List<DiffRow> rows;
        if (root.isArray()) {
            rows = parseRows((ArrayNode) root);
        } else if (root.has("differences") && root.get("differences").isArray()) {
            rows = parseRows((ArrayNode) root.get("differences"));
        } else {
            throw new IOException("Diff result must contain a differences array or be a diff-record array");
        }
        return DiffResult.of(rows, null, null);
    }

    private List<DiffRow> parseRows(ArrayNode array) throws IOException {
        List<DiffRow> rows = new ArrayList<>();
        for (JsonNode node : array) {
            rows.add(parseRow(node));
        }
        return rows;
    }

    private DiffRow parseRow(JsonNode node) throws IOException {
        if (node.has("operation") && node.has("primaryKey")) {
            return parseRecordNode(node);
        }
        return objectMapper.treeToValue(node, DiffRow.class);
    }

    private DiffRow parseRecordNode(JsonNode node) {
        DiffOperation operation = operation(node.path("operation").asText());
        List<Object> primaryKey = values(node.get("primaryKey"));
        List<Object> sourceValues = values(node.get("sourceValues"));
        List<Object> targetValues = values(node.get("targetValues"));
        List<String> columnNames1 = strings(node.get("columnNames1"));
        List<String> columnNames2 = strings(node.get("columnNames2"));
        if (columnNames1.isEmpty()) {
            columnNames1 = strings(node.get("columns"));
        }
        if (columnNames2.isEmpty()) {
            columnNames2 = columnNames1;
        }

        switch (operation) {
            case SOURCE_MISSING:
                return DiffRow.added(primaryKey, targetValues, columnNames2);
            case TARGET_MISSING:
                return DiffRow.removed(primaryKey, sourceValues, columnNames1);
            case MISMATCH:
                return DiffRow.modified(primaryKey, sourceValues, targetValues, columnNames1, columnNames2);
            default:
                throw new IllegalArgumentException("Unsupported diff operation: " + operation);
        }
    }

    private DiffOperation operation(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        try {
            return DiffOperation.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return DiffOperation.fromCode(value.trim());
        }
    }

    private List<Object> values(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(toValue(item)));
            return values;
        }
        return List.of(toValue(node));
    }

    private Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String format(DiffResult result, AnalysisResult analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("# AI Diagnose").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Summary:").append(System.lineSeparator())
                .append("  ").append(analysis.getSummary()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Evidence:").append(System.lineSeparator())
                .append("  differences=").append(result.getDifferenceCount()).append(System.lineSeparator())
                .append("  confidence=").append(String.format("%.0f%%", analysis.getConfidence() * 100))
                .append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Patterns:").append(System.lineSeparator());
        if (analysis.getPatterns() == null || analysis.getPatterns().isEmpty()) {
            builder.append("  - none").append(System.lineSeparator());
        } else {
            for (PatternMatch match : analysis.getPatterns()) {
                builder.append("  - ").append(match.getPatternName())
                        .append(" confidence=").append(String.format("%.0f%%", match.getConfidence() * 100))
                        .append(" affectedRows=").append(match.getAffectedRows())
                        .append(System.lineSeparator())
                        .append("    ").append(match.getDescription()).append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator()).append("Repair Hints:").append(System.lineSeparator());
        if (analysis.getRepairHints() == null || analysis.getRepairHints().isEmpty()) {
            builder.append("  - No deterministic repair hint available. Review diff samples manually.")
                    .append(System.lineSeparator());
        } else {
            analysis.getRepairHints().forEach(hint -> builder.append("  - ").append(hint).append(System.lineSeparator()));
        }
        return builder.toString();
    }
}
