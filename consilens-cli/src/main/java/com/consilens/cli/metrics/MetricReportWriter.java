package com.consilens.cli.metrics;

import com.consilens.cli.config.CliObjectMappers;
import com.consilens.common.io.PathSupport;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes metric comparison results as JSON or Markdown.
 */
public final class MetricReportWriter {

    private MetricReportWriter() {
    }

    public static String write(List<MetricResult> results, String format, Path output) {
        String content = reportFormat(format).equals("json") ? json(results) : markdown(results);
        if (output != null) {
            try {
                PathSupport.ensureParent(output);
                Files.writeString(output, content);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to write metric report to " + output
                        + ": " + exception.getMessage(), exception);
            }
        }
        return content;
    }

    private static String reportFormat(String format) {
        String normalized = format == null ? "markdown" : format.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("json") && !normalized.equals("markdown")) {
            throw new IllegalArgumentException("unsupported metric report format: " + format
                    + " (supported: json, markdown)");
        }
        return normalized;
    }

    private static String json(List<MetricResult> results) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("passed", allPassed(results));
        root.put("metrics", results);
        try {
            return CliObjectMappers.jsonWithJavaTimeIndented().writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize metric report: "
                    + exception.getMessage(), exception);
        }
    }

    private static String markdown(List<MetricResult> results) {
        boolean grouped = results.stream().anyMatch(result -> result.getGroup() != null);
        StringBuilder builder = new StringBuilder();
        builder.append("# Metric diff\n\n");
        builder.append("- passed: ").append(allPassed(results)).append("\n\n");
        builder.append("| ");
        if (grouped) {
            builder.append("Group | ");
        }
        builder.append("Metric | Source | Target | Delta | Relative % | Status |\n");
        builder.append("| ");
        if (grouped) {
            builder.append("--- | ");
        }
        builder.append("--- | --- | --- | --- | --- | --- |\n");
        for (MetricResult result : results) {
            builder.append("| ");
            if (grouped) {
                builder.append(value(result.getGroup())).append(" | ");
            }
            builder.append(result.getMetric())
                    .append(" | ").append(value(result.getSourceValue()))
                    .append(" | ").append(value(result.getTargetValue()))
                    .append(" | ").append(value(result.getDelta()))
                    .append(" | ").append(value(result.getRelativePercent()))
                    .append(" | ").append(result.isPassed() ? "PASS" : "FAIL")
                    .append(" |\n");
        }
        return builder.toString();
    }

    private static boolean allPassed(List<MetricResult> results) {
        return results.stream().allMatch(MetricResult::isPassed);
    }

    private static String value(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
