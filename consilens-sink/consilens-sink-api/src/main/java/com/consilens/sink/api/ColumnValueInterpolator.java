package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.ColumnMapping;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field value interpolation utility for Sink.
 *
 * <p>Supports {@code ${varName}} placeholders in {@link ColumnMapping#getValue()} templates.
 * Templates can mix multiple placeholders and plain text, e.g. {@code "task_${taskId}_${operation}"}.
 *
 * <h3>Built-in variables for diff-record type</h3>
 * <ul>
 *   <li>{@code taskId}              — DiffContext.taskId</li>
 *   <li>{@code sourceTable}         — source table name</li>
 *   <li>{@code targetTable}         — target table name</li>
 *   <li>{@code strategy}            — comparison strategy</li>
 *   <li>{@code algorithm}           — comparison algorithm</li>
 *   <li>{@code operation}           — diff operation type (SOURCE_MISSING / TARGET_MISSING / MISMATCH)</li>
 *   <li>{@code primaryKey}          — primary key string</li>
 *   <li>{@code changedColumns}      — changed columns JSON array (alias for changedColumns1)</li>
 *   <li>{@code changedColumns1}     — source-side changed columns JSON array</li>
 *   <li>{@code changedColumns2}     — target-side changed columns JSON array</li>
 *   <li>{@code src.colName}         — source-side value of specified column</li>
 *   <li>{@code tgt.colName}         — target-side value of specified column</li>
 *   <li>{@code timestamp}           — current time ISO-8601 string</li>
 *   <li>{@code attr.key}            — DiffContext custom attribute</li>
 * </ul>
 *
 * <h3>Built-in variables for result type</h3>
 * In addition to taskId / sourceTable / targetTable / strategy / algorithm / timestamp / attr.key:
 * <ul>
 *   <li>{@code status}              — execution status (EQUAL / DIFF)</li>
 *   <li>{@code totalDifferences}    — total differences</li>
 *   <li>{@code sourceMissingCount}  — source-missing row count</li>
 *   <li>{@code targetMissingCount}  — target-missing row count</li>
 *   <li>{@code mismatchCount}       — mismatched row count</li>
 *   <li>{@code sourceRowCount}      — source row count</li>
 *   <li>{@code targetRowCount}      — target row count</li>
 *   <li>{@code statistics_json}     — full statistics as a JSON string:
 *       {@code {"totalCount":N,"mismatchCount":N,"sourceMissingCount":N,"targetMissingCount":N,"totalDiffCount":N,"accuracyRate":N}}
 *       where {@code accuracyRate = (totalCount - totalDiffCount) / totalCount * 100}, rounded to 2 decimal places.</li>
 * </ul>
 */
public class ColumnValueInterpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private ColumnValueInterpolator() {}

    // ---- Public entry points ----

    /**
     * Resolve a single FieldMapping in the diff-record context.
     * Returns {@code field.getDefaultValue()} if resolved value is empty.
     */
    public static String resolveField(ColumnMapping field, DiffContext ctx, DiffRow row) {
        if (field == null) return null;
        if (field.getValue() == null) {
            return resolveDefaultValue(field, varName -> resolveDiffRecordVar(varName, ctx, row));
        }
        String resolved = interpolate(field.getValue(), varName -> resolveDiffRecordVar(varName, ctx, row));
        if (resolved.isEmpty()) {
            return resolveDefaultValue(field, varName -> resolveDiffRecordVar(varName, ctx, row));
        }
        return resolved;
    }

    /**
     * Resolve a single FieldMapping in the result context.
     * Returns {@code field.getDefaultValue()} if resolved value is empty.
     */
    public static String resolveField(ColumnMapping field, DiffContext ctx, DiffResult result) {
        if (field == null) return null;
        if (field.getValue() == null) {
            return resolveDefaultValue(field, varName -> resolveResultVar(varName, ctx, result));
        }
        String resolved = interpolate(field.getValue(), varName -> resolveResultVar(varName, ctx, result));
        if (resolved == null || resolved.isEmpty()) {
            return resolveDefaultValue(field, varName -> resolveResultVar(varName, ctx, result));
        }
        return resolved;
    }

    /**
     * Resolve a path/filename template supporting all built-in diff-record variables.
     */
    public static String resolvePath(String template, DiffContext ctx) {
        if (template == null) return null;
        return interpolate(template, varName -> resolveDiffRecordVar(varName, ctx, null));
    }

    // ---- Interpolation engine ----

    private static String interpolate(String template, java.util.function.Function<String, String> resolver) {
        if (template == null) {
            return null;
        }
        if (!template.contains("${")) {
            return template;
        }
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String varName = m.group(1);
            String resolved = resolver.apply(varName);
            m.appendReplacement(sb, resolved != null ? Matcher.quoteReplacement(resolved) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveDefaultValue(String defaultValue, java.util.function.Function<String, String> resolver) {
        if (defaultValue == null) {
            return null;
        }
        return interpolate(defaultValue, resolver);
    }

    private static String resolveDefaultValue(ColumnMapping field, java.util.function.Function<String, String> resolver) {
        return resolveDefaultValue(field.getDefaultValue(), resolver);
    }

    // ---- Variable resolvers ----

    private static String resolveDiffRecordVar(String varName, DiffContext ctx, DiffRow row) {
        // Context variables
        switch (varName) {
            case "taskId":
                return ctx.getTaskId();
            case "sourceTable":
                return ctx.getSourceTablePath() != null ? ctx.getSourceTablePath().getTableName() : null;
            case "targetTable":
                return ctx.getTargetTablePath() != null ? ctx.getTargetTablePath().getTableName() : null;
            case "strategy":
                return ctx.getStrategy();
            case "algorithm":
                return ctx.getAlgorithm();
            case "timestamp":
                return Instant.now().toString();
            case "TASK_ID":
                // Legacy alias for backward compatibility with existing ${TASK_ID} usage
                return ctx.getTaskId();
            default:
                break;
        }
        // DiffRow variables
        if (row != null) {
            switch (varName) {
                case "operation":
                    return row.getOperation().getCode();
                case "primaryKey":
                    return row.getPrimaryKeyString();
                case "changedColumns":
                case "changedColumns1":
                    return toJsonArray(row.getChangedColumns1());
                case "changedColumns2":
                    return toJsonArray(row.getChangedColumns2());
                default:
                    break;
            }
            if (varName.startsWith("src.")) {
                Object val = row.getSourceValue(varName.substring(4));
                return val != null ? val.toString() : null;
            }
            if (varName.startsWith("tgt.")) {
                Object val = row.getTargetValue(varName.substring(4));
                return val != null ? val.toString() : null;
            }
        }
        // Custom attributes
        if (varName.startsWith("attr.")) {
            Object val = ctx.getAttribute(varName.substring(5));
            return val != null ? val.toString() : null;
        }
        return null;
    }

    private static String resolveResultVar(String varName, DiffContext ctx, DiffResult result) {
        // Common context variables
        switch (varName) {
            case "taskId":
                return ctx.getTaskId();
            case "sourceTable":
                return ctx.getSourceTablePath() != null ? ctx.getSourceTablePath().getTableName() : null;
            case "targetTable":
                return ctx.getTargetTablePath() != null ? ctx.getTargetTablePath().getTableName() : null;
            case "strategy":
                return ctx.getStrategy();
            case "algorithm":
                return ctx.getAlgorithm();
            case "timestamp":
                return Instant.now().toString();
            case "TASK_ID":
                return ctx.getTaskId();
            default:
                break;
        }
        // Result-specific variables
        if (result != null) {
            DiffResult.DiffStatistics stats = result.getStatistics();
            switch (varName) {
                case "status":
                    return result.hasDifferences() ? "DIFF" : "EQUAL";
                case "totalDifferences":
                    return stats != null ? String.valueOf(stats.getTotalDifferences()) : "0";
                case "sourceMissingCount":
                    return stats != null ? String.valueOf(stats.getSourceMissingCount()) : "0";
                case "targetMissingCount":
                    return stats != null ? String.valueOf(stats.getTargetMissingCount()) : "0";
                case "mismatchCount":
                    return stats != null ? String.valueOf(stats.getMismatchCount()) : "0";
                case "sourceRowCount":
                    return stats != null ? String.valueOf(stats.getSourceRowCount()) : "0";
                case "targetRowCount":
                    return stats != null ? String.valueOf(stats.getTargetRowCount()) : "0";
                case "statistics_json":
                    return buildStatisticsJson(stats);
                default:
                    break;
            }
        }
        // Custom attributes
        if (varName.startsWith("attr.")) {
            Object val = ctx.getAttribute(varName.substring(5));
            return val != null ? val.toString() : null;
        }
        return null;
    }

    // ---- Helpers ----

    /**
     * Build the statistics_json string from DiffStatistics.
     * accuracyRate = (totalCount - totalDiffCount) / totalCount * 100, rounded to 2 decimal places.
     */
    private static String buildStatisticsJson(DiffResult.DiffStatistics stats) {
        if (stats == null) {
            return "{\"totalCount\":0,\"mismatchCount\":0,\"sourceMissingCount\":0,\"targetMissingCount\":0,\"totalDiffCount\":0,\"accuracyRate\":0.00}";
        }
        long totalCount = stats.getSourceRowCount();
        long mismatchCount = stats.getMismatchCount();
        long sourceMissingCount = stats.getSourceMissingCount();
        long targetMissingCount = stats.getTargetMissingCount();
        long totalDiffCount = stats.getTotalDifferences();
        double accuracyRate = totalCount > 0
                ? Math.round((double) (totalCount - totalDiffCount) / totalCount * 10000.0) / 100.0
                : 0.0;
        return String.format(
                "{\"totalCount\":%d,\"mismatchCount\":%d,\"sourceMissingCount\":%d,\"targetMissingCount\":%d,\"totalDiffCount\":%d,\"accuracyRate\":%.2f}",
                totalCount, mismatchCount, sourceMissingCount, targetMissingCount, totalDiffCount, accuracyRate);
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
