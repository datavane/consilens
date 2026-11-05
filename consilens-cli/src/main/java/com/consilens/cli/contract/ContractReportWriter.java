package com.consilens.cli.contract;

import com.consilens.cli.config.CliObjectMappers;
import com.consilens.common.io.PathSupport;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Serializes a contract diff as JSON or Markdown.
 */
public final class ContractReportWriter {

    private ContractReportWriter() {
    }

    public static String write(ContractDiff diff, String format, Path output) {
        String content = reportFormat(format).equals("json") ? json(diff) : markdown(diff);
        if (output != null) {
            try {
                PathSupport.ensureParent(output);
                Files.writeString(output, content);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to write contract report to " + output
                        + ": " + exception.getMessage(), exception);
            }
        }
        return content;
    }

    private static String reportFormat(String format) {
        String normalized = format == null ? "markdown" : format.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("json") && !normalized.equals("markdown")) {
            throw new IllegalArgumentException("unsupported contract report format: " + format
                    + " (supported: json, markdown)");
        }
        return normalized;
    }

    private static String json(ContractDiff diff) {
        try {
            return CliObjectMappers.jsonWithJavaTimeIndented().writeValueAsString(diff);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize contract diff: "
                    + exception.getMessage(), exception);
        }
    }

    private static String markdown(ContractDiff diff) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Contract diff\n\n");
        builder.append("- source: `").append(diff.getSourceName()).append("`\n");
        builder.append("- target: `").append(diff.getTargetName()).append("`\n");
        builder.append("- identical: ").append(diff.isIdentical()).append("\n\n");
        if (diff.isIdentical()) {
            builder.append("No schema changes.\n");
            return builder.toString();
        }
        builder.append("| Kind | Column | Source | Target |\n");
        builder.append("| --- | --- | --- | --- |\n");
        for (ContractDiff.Change change : diff.getChanges()) {
            builder.append("| ").append(change.getKind())
                    .append(" | ").append(value(change.getColumn()))
                    .append(" | ").append(value(change.getSourceValue()))
                    .append(" | ").append(value(change.getTargetValue()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private static String value(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return "`" + value.replace("|", "\\|") + "`";
    }
}
