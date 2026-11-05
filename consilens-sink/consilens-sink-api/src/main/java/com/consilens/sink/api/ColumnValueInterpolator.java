package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.ColumnMapping;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /** Prefix for source-side row values, e.g. {@code ${src.id}}. */
    private static final String SRC_PREFIX = "src.";
    /** Prefix for target-side row values, e.g. {@code ${tgt.id}}. */
    private static final String TGT_PREFIX = "tgt.";
    /** Prefix for DiffContext custom attributes, e.g. {@code ${attr.batch}}. */
    private static final String ATTR_PREFIX = "attr.";

    /**
     * Legacy alias for {@code taskId}, kept for backward compatibility with
     * existing {@code ${TASK_ID}} templates (see {@code resolveDiffRecordVar}).
     *
     * @deprecated use {@code ${taskId}} instead; retained only so old configs keep working.
     */
    @Deprecated
    private static final String TASK_ID_ALIAS = "TASK_ID";

    /** Variables shared by the diff-record and result contexts, in resolution order. */
    private static final Map<String, Function<DiffContext, Object>> COMMON_CONTEXT_VARS;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        Map<String, Function<DiffContext, Object>> vars = new LinkedHashMap<>();
        vars.put("taskId", DiffContext::getTaskId);
        vars.put("sourceTable", ctx -> ctx.getSourceTablePath() != null
                ? ctx.getSourceTablePath().getTableName() : null);
        vars.put("targetTable", ctx -> ctx.getTargetTablePath() != null
                ? ctx.getTargetTablePath().getTableName() : null);
        vars.put("strategy", DiffContext::getStrategy);
        vars.put("algorithm", DiffContext::getAlgorithm);
        vars.put("timestamp", ctx -> Instant.now().toString());
        vars.put(TASK_ID_ALIAS, DiffContext::getTaskId);
        COMMON_CONTEXT_VARS = Collections.unmodifiableMap(vars);
    }

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

    private static String interpolate(String template, Function<String, String> resolver) {
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

    private static String resolveDefaultValue(String defaultValue, Function<String, String> resolver) {
        if (defaultValue == null) {
            return null;
        }
        return interpolate(defaultValue, resolver);
    }

    private static String resolveDefaultValue(ColumnMapping field, Function<String, String> resolver) {
        return resolveDefaultValue(field.getDefaultValue(), resolver);
    }

    // ---- Variable resolvers ----

    private static String resolveDiffRecordVar(String varName, DiffContext ctx, DiffRow row) {
        String common = resolveCommonVar(varName, ctx);
        if (common != null) {
            return common;
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
        }
        return resolvePrefixedVar(varName, ctx, row);
    }

    private static String resolveResultVar(String varName, DiffContext ctx, DiffResult result) {
        String common = resolveCommonVar(varName, ctx);
        if (common != null) {
            return common;
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
        return resolvePrefixedVar(varName, ctx, null);
    }

    /** Resolve the single shared set of context variables; {@code null} when unknown. */
    private static String resolveCommonVar(String varName, DiffContext ctx) {
        Function<DiffContext, Object> resolver = COMMON_CONTEXT_VARS.get(varName);
        if (resolver == null) {
            return null;
        }
        return toStringOrNull(resolver.apply(ctx));
    }

    /**
     * Resolve the {@code src.} / {@code tgt.} / {@code attr.} prefixes in one place.
     * Prefix matching and substring offsets are centralized here.
     */
    private static String resolvePrefixedVar(String varName, DiffContext ctx, DiffRow row) {
        if (varName.startsWith(SRC_PREFIX)) {
            return row == null ? null : toStringOrNull(row.getSourceValue(
                    varName.substring(SRC_PREFIX.length())));
        }
        if (varName.startsWith(TGT_PREFIX)) {
            return row == null ? null : toStringOrNull(row.getTargetValue(
                    varName.substring(TGT_PREFIX.length())));
        }
        if (varName.startsWith(ATTR_PREFIX)) {
            return toStringOrNull(ctx.getAttribute(varName.substring(ATTR_PREFIX.length())));
        }
        return null;
    }

    private static String toStringOrNull(Object value) {
        return value != null ? value.toString() : null;
    }

    // ---- Helpers ----

    /**
     * Build the statistics_json string from DiffStatistics.
     * accuracyRate = (totalCount - totalDiffCount) / totalCount * 100, rounded to 2 decimal places.
     */
    private static String buildStatisticsJson(DiffResult.DiffStatistics stats) {
        long totalCount = 0;
        long mismatchCount = 0;
        long sourceMissingCount = 0;
        long targetMissingCount = 0;
        long totalDiffCount = 0;
        if (stats != null) {
            totalCount = stats.getSourceRowCount();
            mismatchCount = stats.getMismatchCount();
            sourceMissingCount = stats.getSourceMissingCount();
            targetMissingCount = stats.getTargetMissingCount();
            totalDiffCount = stats.getTotalDifferences();
        }
        double accuracyRate = totalCount > 0
                ? Math.round((double) (totalCount - totalDiffCount) / totalCount * 10000.0) / 100.0
                : 0.0;
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalCount", totalCount);
        statistics.put("mismatchCount", mismatchCount);
        statistics.put("sourceMissingCount", sourceMissingCount);
        statistics.put("targetMissingCount", targetMissingCount);
        statistics.put("totalDiffCount", totalDiffCount);
        statistics.put("accuracyRate", accuracyRate);
        try {
            return OBJECT_MAPPER.writeValueAsString(statistics);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize statistics JSON", e);
        }
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            // 手写拼接漏掉换行/制表/控制字符的转义，会写出非法 JSON
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize changed columns", e);
        }
    }
}
