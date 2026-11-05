package com.consilens.cli.metrics;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Parsed metric expression:
 *
 * <pre>
 *   count            row count
 *   count:col        non-null count
 *   nulls:col        null count
 *   sum:col / min:col / max:col / avg:col
 * </pre>
 */
@Getter
@EqualsAndHashCode
public final class MetricDefinition {

    public enum Kind {
        ROW_COUNT,
        NULL_COUNT,
        NON_NULL_COUNT,
        SUM,
        MIN,
        MAX,
        AVG
    }

    private final Kind kind;
    private final String column;

    private MetricDefinition(Kind kind, String column) {
        this.kind = kind;
        this.column = column;
    }

    public static MetricDefinition parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("metric expression must not be blank");
        }
        String text = expression.trim();
        int colon = text.indexOf(':');
        String name = colon < 0 ? text : text.substring(0, colon).trim();
        String column = colon < 0 ? null : text.substring(colon + 1).trim();
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        if ("count".equals(normalized)) {
            return column == null || column.isEmpty()
                    ? new MetricDefinition(Kind.ROW_COUNT, null)
                    : new MetricDefinition(Kind.NON_NULL_COUNT, column);
        }
        if ("nulls".equals(normalized) || "null".equals(normalized)) {
            requireColumn(expression, column);
            return new MetricDefinition(Kind.NULL_COUNT, column);
        }
        if ("sum".equals(normalized)) {
            requireColumn(expression, column);
            return new MetricDefinition(Kind.SUM, column);
        }
        if ("min".equals(normalized)) {
            requireColumn(expression, column);
            return new MetricDefinition(Kind.MIN, column);
        }
        if ("max".equals(normalized)) {
            requireColumn(expression, column);
            return new MetricDefinition(Kind.MAX, column);
        }
        if ("avg".equals(normalized) || "mean".equals(normalized)) {
            requireColumn(expression, column);
            return new MetricDefinition(Kind.AVG, column);
        }
        throw new IllegalArgumentException("Unsupported metric: " + expression
                + " (supported: count, count:col, nulls:col, sum:col, min:col, max:col, avg:col)");
    }

    public boolean isNumeric() {
        return kind == Kind.SUM || kind == Kind.MIN || kind == Kind.MAX || kind == Kind.AVG;
    }

    public String label() {
        if (column == null) {
            return "count";
        }
        if (kind == Kind.NULL_COUNT) {
            return "nulls:" + column;
        }
        if (kind == Kind.NON_NULL_COUNT) {
            return "count:" + column;
        }
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + column;
    }

    private static void requireColumn(String expression, String column) {
        if (column == null || column.isEmpty()) {
            throw new IllegalArgumentException("Metric requires a column: " + expression);
        }
    }
}
