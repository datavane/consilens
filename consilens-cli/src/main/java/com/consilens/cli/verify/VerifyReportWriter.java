package com.consilens.cli.verify;

import com.consilens.common.io.PathSupport;
import com.consilens.sdk.verify.PolicyCheck;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes verify results in CI-friendly formats. All writers are deterministic
 * and contain no secrets.
 */
public final class VerifyReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private VerifyReportWriter() {
    }

    public static List<Path> write(VerifyResult result, List<String> formats, Path outputDir) throws IOException {
        PathSupport.ensureDirectory(outputDir);
        List<Path> written = new ArrayList<>();
        for (String format : formats) {
            switch (format.toLowerCase(java.util.Locale.ROOT)) {
                case "json":
                    written.add(writeJson(result, outputDir.resolve("verify-result.json")));
                    break;
                case "junit":
                    written.add(writeJunit(result, outputDir.resolve("verify-junit.xml")));
                    break;
                case "sarif":
                    written.add(writeSarif(result, outputDir.resolve("verify.sarif")));
                    break;
                case "markdown":
                case "md":
                    written.add(writeMarkdown(result, outputDir.resolve("verify-summary.md")));
                    break;
                default:
                    throw new IllegalArgumentException("unsupported verify format: " + format);
            }
        }
        return written;
    }

    private static Path writeJson(VerifyResult result, Path path) throws IOException {
        Files.writeString(path, MAPPER.writeValueAsString(result), StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeJunit(VerifyResult result, Path path) throws IOException {
        List<PolicyCheck> checks = result.getChecks() == null ? List.of() : result.getChecks();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"consilens.verify\" tests=\"").append(checks.size())
                .append("\" failures=\"").append(failureCount(result))
                .append("\" skipped=\"").append(skippedCount(result)).append("\">\n");
        for (PolicyCheck check : checks) {
            xml.append("  <testcase name=\"").append(escape(check.getName())).append("\" classname=\"consilens.verify\"");
            boolean failed = check.getPassed() != null && !check.getPassed();
            if (failed) {
                xml.append(">\n");
                xml.append("    <failure message=\"").append(escape(failureMessage(check)))
                        .append("\" type=\"VerificationFailure\"/>").append("\n");
                xml.append("  </testcase>\n");
            } else if (check.getPassed() == null) {
                // 不可评估（passed=null）不是"通过"：渲染成 skipped 并带上原因，
                // 否则 CI 里看起来这项检查成功，原因也被丢掉。
                xml.append(">\n");
                xml.append("    <skipped message=\"").append(escape(skippedMessage(check)))
                        .append("\"/>").append("\n");
                xml.append("  </testcase>\n");
            } else {
                xml.append("/>\n");
            }
        }
        if (result.getMessage() != null && checks.isEmpty()) {
            xml.append("  <testcase name=\"verify\" classname=\"consilens.verify\">\n");
            xml.append("    <failure message=\"").append(escape(result.getMessage())).append("\"/>").append("\n");
            xml.append("  </testcase>\n");
        }
        xml.append("</testsuite>\n");
        Files.writeString(path, xml.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeSarif(VerifyResult result, Path path) throws IOException {
        List<PolicyCheck> checks = result.getChecks() == null ? List.of() : result.getChecks();
        StringBuilder sarif = new StringBuilder();
        sarif.append("{\n  \"version\": \"2.1.0\",\n");
        sarif.append("  \"$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n");
        sarif.append("  \"runs\": [{\n    \"tool\": {\"driver\": {\"name\": \"consilens\"}},\n");
        sarif.append("    \"results\": [\n");
        boolean first = true;
        for (PolicyCheck check : checks) {
            if (check.getPassed() != null && !check.getPassed()) {
                if (!first) {
                    sarif.append(",\n");
                }
                first = false;
                sarif.append("      {\"ruleId\": \"CONSILENS_").append(escape(check.getName()).toUpperCase(java.util.Locale.ROOT))
                        .append("\", \"level\": \"error\", \"message\": {\"text\": \"")
                        .append(escape(failureMessage(check))).append("\"}}");
            }
        }
        if (result.getVerdict() == Verdict.INCONCLUSIVE) {
            if (!first) {
                sarif.append(",\n");
            }
            sarif.append("      {\"ruleId\": \"CONSILENS_INCONCLUSIVE\", \"level\": \"warning\", \"message\": {\"text\": \"")
                    .append(escape(result.getMessage() == null ? "verification inconclusive" : result.getMessage()))
                    .append("\"}}");
        }
        sarif.append("\n    ]\n  }]\n}\n");
        Files.writeString(path, sarif.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeMarkdown(VerifyResult result, Path path) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Consilens verify\n\n");
        markdown.append("- verdict: **").append(result.getVerdict()).append("**\n");
        if (result.getSummary() != null) {
            markdown.append("- totalDifferences: ").append(result.getSummary().getTotalDifferences()).append("\n");
            markdown.append("- mismatchCount: ").append(result.getSummary().getMismatchCount()).append("\n");
            markdown.append("- sourceRows: ").append(result.getSummary().getSourceRowCount()).append("\n");
            markdown.append("- targetRows: ").append(result.getSummary().getTargetRowCount()).append("\n");
            if (result.getSummary().getWithinToleranceCount() > 0) {
                markdown.append("- withinToleranceCount: ")
                        .append(result.getSummary().getWithinToleranceCount()).append("\n");
            }
        }
        if (result.getDelta() != null) {
            markdown.append("- delta: new=").append(result.getDelta().getNewDifferences())
                    .append(", fixed=").append(result.getDelta().getFixedDifferences())
                    .append(", persisting=").append(result.getDelta().getPersistingDifferences()).append("\n");
        }
        markdown.append("\n| check | passed | actual | limit |\n| --- | --- | --- | --- |\n");
        for (PolicyCheck check : result.getChecks() == null ? List.<PolicyCheck>of() : result.getChecks()) {
            markdown.append("| ").append(check.getName()).append(" | ")
                    .append(check.getPassed()).append(" | ")
                    .append(check.getActual()).append(" | ")
                    .append(check.getLimit()).append(" |\n");
        }
        List<com.consilens.core.diff.DiffRow> withinTolerance = result.getWithinToleranceDifferences();
        if (withinTolerance != null && !withinTolerance.isEmpty()) {
            markdown.append("\n## 容差内（未计入差异）\n\n");
            markdown.append("| primaryKey | changedColumns | source | target |\n");
            markdown.append("| --- | --- | --- | --- |\n");
            for (com.consilens.core.diff.DiffRow row : withinTolerance) {
                Object columns = row.getMetadata() == null ? null
                        : row.getMetadata().get(com.consilens.core.diff.DiffRow.METADATA_TOLERANCE_COLUMNS);
                markdown.append("| ").append(markdownCell(row.getPrimaryKeyString()))
                        .append(" | ").append(markdownCell(String.valueOf(columns)))
                        .append(" | ").append(markdownCell(String.valueOf(row.getAllSourceValues())))
                        .append(" | ").append(markdownCell(String.valueOf(row.getAllTargetValues())))
                        .append(" |\n");
            }
            markdown.append("\n这些行精确值不等但落在容差内，不计入 totalDifferences。\n");
        }
        if (result.getMessage() != null) {
            markdown.append("\n").append(result.getMessage()).append("\n");
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private static long skippedCount(VerifyResult result) {
        long count = 0;
        for (PolicyCheck check : result.getChecks() == null ? List.<PolicyCheck>of() : result.getChecks()) {
            if (check.getPassed() == null) {
                count++;
            }
        }
        return count;
    }

    private static long failureCount(VerifyResult result) {
        long count = 0;
        for (PolicyCheck check : result.getChecks() == null ? List.<PolicyCheck>of() : result.getChecks()) {
            if (check.getPassed() != null && !check.getPassed()) {
                count++;
            }
        }
        return count;
    }

    private static String skippedMessage(PolicyCheck check) {
        if (check.getMessage() != null && !check.getMessage().isBlank()) {
            return check.getMessage();
        }
        return check.getName() + " was not evaluable";
    }

    private static String failureMessage(PolicyCheck check) {
        if (check.getMessage() != null && !check.getMessage().isBlank()) {
            return check.getMessage();
        }
        return check.getName() + " failed: actual=" + check.getActual() + ", limit=" + check.getLimit();
    }

    /** 表格单元格转义：`|` 会截断列、换行会截断行；HTML 实体转义挡不住这两种。 */
    private static String markdownCell(String value) {
        if (value == null) {
            return "";
        }
        // 先转义反斜杠再转义管道：否则值里的 "\\|" 会变成 "\\\|"，GFM 仍把管道当列分隔
        return escape(value).replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
