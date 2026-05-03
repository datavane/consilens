package com.consilens.sink.table;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.ColumnValueInterpolator;
import com.consilens.sink.api.model.ColumnMapping;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TypedValueResolver {

    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\$\\{([^}]+)}$");

    private TypedValueResolver() {
    }

    static Object resolve(ColumnMapping field, DiffContext context, DiffRow row) {
        if (field == null) {
            return null;
        }
        Object directValue = resolveDirect(field.getValue(), variable -> resolveDiffRecordVariable(variable, context, row));
        if (directValue != null) {
            return directValue;
        }
        Object defaultValue = resolveDirect(field.getDefaultValue(), variable -> resolveDiffRecordVariable(variable, context, row));
        if (defaultValue != null) {
            return defaultValue;
        }
        return ColumnValueInterpolator.resolveField(field, context, row);
    }

    static Object resolve(ColumnMapping field, DiffContext context, DiffResult result) {
        if (field == null) {
            return null;
        }
        Object directValue = resolveDirect(field.getValue(), variable -> resolveResultVariable(variable, context, result));
        if (directValue != null) {
            return directValue;
        }
        Object defaultValue = resolveDirect(field.getDefaultValue(), variable -> resolveResultVariable(variable, context, result));
        if (defaultValue != null) {
            return defaultValue;
        }
        return ColumnValueInterpolator.resolveField(field, context, result);
    }

    static Object resolveError(ColumnMapping field, DiffContext context, Throwable error) {
        if (field == null) {
            return null;
        }
        Object directValue = resolveDirect(field.getValue(), variable -> resolveErrorVariable(variable, context, error));
        if (directValue != null) {
            return directValue;
        }
        Object defaultValue = resolveDirect(field.getDefaultValue(), variable -> resolveErrorVariable(variable, context, error));
        if (defaultValue != null) {
            return defaultValue;
        }
        String resolved = ColumnValueInterpolator.resolveField(field, context, (DiffResult) null);
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }

        String normalizedName = field.getName() != null ? field.getName().trim().toLowerCase(Locale.ROOT) : "";
        switch (normalizedName) {
            case "run_status":
            case "status":
            case "execution_status":
                return "ERROR";
            case "error_message":
            case "message":
            case "error":
                return error != null ? error.getMessage() : null;
            case "task_id":
            case "execution_id":
            case "nl_dq_execution_id":
                return context.getTaskId();
            case "completed_at":
            case "timestamp":
                return Instant.now();
            default:
                return null;
        }
    }

    private static Object resolveDirect(String template, VariableResolver resolver) {
        if (template == null) {
            return null;
        }
        Matcher matcher = SINGLE_PLACEHOLDER.matcher(template.trim());
        if (!matcher.matches()) {
            return null;
        }
        return resolver.resolve(matcher.group(1));
    }

    private static Object resolveDiffRecordVariable(String variable, DiffContext context, DiffRow row) {
        switch (variable) {
            case "taskId":
            case "TASK_ID":
                return context.getTaskId();
            case "sourceTable":
                return context.getSourceTablePath() != null ? context.getSourceTablePath().getTableName() : null;
            case "targetTable":
                return context.getTargetTablePath() != null ? context.getTargetTablePath().getTableName() : null;
            case "strategy":
                return context.getStrategy();
            case "algorithm":
                return context.getAlgorithm();
            case "timestamp":
                return Instant.now();
            case "operation":
                return row != null && row.getOperation() != null ? row.getOperation().getCode() : null;
            case "primaryKey":
                if (row == null || row.getPrimaryKey() == null || row.getPrimaryKey().isEmpty()) {
                    return null;
                }
                return row.getPrimaryKey().size() == 1 ? row.getPrimaryKey().get(0) : row.getPrimaryKeyString();
            case "changedColumns":
            case "changedColumns1":
                return row != null ? toJsonArray(row.getChangedColumns1()) : null;
            case "changedColumns2":
                return row != null ? toJsonArray(row.getChangedColumns2()) : null;
            default:
                if (variable.startsWith("src.")) {
                    return row != null ? row.getSourceValue(variable.substring(4)) : null;
                }
                if (variable.startsWith("tgt.")) {
                    return row != null ? row.getTargetValue(variable.substring(4)) : null;
                }
                if (variable.startsWith("attr.")) {
                    return context.getAttribute(variable.substring(5));
                }
                return null;
        }
    }

    private static Object resolveResultVariable(String variable, DiffContext context, DiffResult result) {
        switch (variable) {
            case "taskId":
            case "TASK_ID":
                return context.getTaskId();
            case "sourceTable":
                return context.getSourceTablePath() != null ? context.getSourceTablePath().getTableName() : null;
            case "targetTable":
                return context.getTargetTablePath() != null ? context.getTargetTablePath().getTableName() : null;
            case "strategy":
                return context.getStrategy();
            case "algorithm":
                return context.getAlgorithm();
            case "timestamp":
                return Instant.now();
            case "status":
                return result != null ? (result.hasDifferences() ? "DIFF" : "EQUAL") : null;
            case "totalDifferences":
                return result != null && result.getStatistics() != null ? result.getStatistics().getTotalDifferences() : 0L;
            case "sourceMissingCount":
                return result != null && result.getStatistics() != null ? result.getStatistics().getSourceMissingCount() : 0L;
            case "targetMissingCount":
                return result != null && result.getStatistics() != null ? result.getStatistics().getTargetMissingCount() : 0L;
            case "mismatchCount":
                return result != null && result.getStatistics() != null ? result.getStatistics().getMismatchCount() : 0L;
            case "sourceRowCount":
                return result != null && result.getStatistics() != null ? result.getStatistics().getSourceRowCount() : 0L;
            case "targetRowCount":
                return result != null && result.getStatistics() != null ? result.getStatistics().getTargetRowCount() : 0L;
            default:
                if ("statistics_json".equals(variable) && result != null) {
                    ColumnMapping mapping = new ColumnMapping();
                    mapping.setValue("${statistics_json}");
                    return ColumnValueInterpolator.resolveField(mapping, context, result);
                }
                if (variable.startsWith("attr.")) {
                    return context.getAttribute(variable.substring(5));
                }
                return null;
        }
    }

    private static Object resolveErrorVariable(String variable, DiffContext context, Throwable error) {
        switch (variable) {
            case "taskId":
            case "TASK_ID":
                return context.getTaskId();
            case "sourceTable":
                return context.getSourceTablePath() != null ? context.getSourceTablePath().getTableName() : null;
            case "targetTable":
                return context.getTargetTablePath() != null ? context.getTargetTablePath().getTableName() : null;
            case "strategy":
                return context.getStrategy();
            case "algorithm":
                return context.getAlgorithm();
            case "timestamp":
            case "completed_at":
                return Instant.now();
            case "status":
            case "run_status":
                return "ERROR";
            case "error":
            case "error_message":
            case "message":
                return error != null ? error.getMessage() : null;
            default:
                if (variable.startsWith("attr.")) {
                    return context.getAttribute(variable.substring(5));
                }
                return null;
        }
    }

    private static String toJsonArray(java.util.List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    @FunctionalInterface
    private interface VariableResolver {
        Object resolve(String variable);
    }
}
