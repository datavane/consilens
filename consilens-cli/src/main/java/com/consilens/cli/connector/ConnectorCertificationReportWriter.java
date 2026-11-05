package com.consilens.cli.connector;

import com.consilens.cli.config.CliObjectMappers;
import com.consilens.common.io.PathSupport;
import com.consilens.connector.testkit.CertificationReport;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes connector certification results as JSON or Markdown.
 */
public final class ConnectorCertificationReportWriter {

    private ConnectorCertificationReportWriter() {
    }

    public static String write(List<SideCertification> sides, String format, Path output) {
        String content = reportFormat(format).equals("json") ? json(sides) : markdown(sides);
        if (output != null) {
            try {
                PathSupport.ensureParent(output);
                Files.writeString(output, content);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to write certification report to "
                        + output + ": " + exception.getMessage(), exception);
            }
        }
        return content;
    }

    private static String reportFormat(String format) {
        String normalized = format == null ? "markdown" : format.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("json") && !normalized.equals("markdown")) {
            throw new IllegalArgumentException("unsupported certification report format: " + format
                    + " (supported: json, markdown)");
        }
        return normalized;
    }

    public static boolean allPassed(List<SideCertification> sides) {
        return !sides.isEmpty() && sides.stream().allMatch(side -> side.getReport().isPassed());
    }

    private static String json(List<SideCertification> sides) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("passed", allPassed(sides));
        List<Map<String, Object>> reports = new ArrayList<>();
        for (SideCertification side : sides) {
            CertificationReport report = side.getReport();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("side", side.getSide());
            item.put("connectorType", report.getConnectorType());
            item.put("resource", report.getResource());
            item.put("passed", report.isPassed());
            item.put("recordsRead", report.getRecordsRead());
            item.put("checks", report.getChecks());
            reports.add(item);
        }
        root.put("sides", reports);
        try {
            return CliObjectMappers.jsonWithJavaTimeIndented().writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize certification report: "
                    + exception.getMessage(), exception);
        }
    }

    private static String markdown(List<SideCertification> sides) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Connector certification\n\n");
        builder.append("- passed: ").append(allPassed(sides)).append("\n\n");
        for (SideCertification side : sides) {
            CertificationReport report = side.getReport();
            builder.append("## ").append(side.getSide()).append(": ")
                    .append(report.getConnectorType()).append("\n\n");
            builder.append("- resource: `").append(value(report.getResource())).append("`\n");
            builder.append("- passed: ").append(report.isPassed()).append("\n");
            builder.append("- records read: ").append(report.getRecordsRead()).append("\n\n");
            builder.append("| Check | Status | Message |\n");
            builder.append("| --- | --- | --- |\n");
            for (CertificationReport.Check check : report.getChecks()) {
                builder.append("| ").append(check.getName())
                        .append(" | ").append(check.isPassed() ? "PASS" : "FAIL")
                        .append(" | ").append(value(check.getMessage()))
                        .append(" |\n");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String value(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.replace("|", "\\|");
    }
}
