package com.consilens.cli.metrics;

import com.consilens.cli.service.DuckDbErrors;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates metrics for local file sources through DuckDB SQL. This is an
 * opt-in fast path; the default engine still scans CanonicalRecords so every
 * connector keeps identical semantics.
 */
final class DuckDbMetrics {

    private DuckDbMetrics() {
    }

    static List<BigDecimal> compute(ConnectorConfig config, List<MetricDefinition> definitions) {
        String table = resolvedTable(config);
        try (Connection connection = connect()) {
            String sql = buildSql(table, definitions, columnTypes(connection, table));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new IllegalStateException("DuckDB returned no aggregate row");
                }
                List<BigDecimal> values = new ArrayList<>();
                for (int index = 1; index <= definitions.size(); index++) {
                    values.add(decimal(result.getObject(index), definitions.get(index - 1)));
                }
                return values;
            } catch (SQLException exception) {
                throw new IllegalArgumentException("DuckDB metric query failed: "
                        + DuckDbErrors.message(exception) + " (sql=" + sql + ")", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to close DuckDB connection", exception);
        }
    }

    static List<GroupedMetrics> computeGrouped(ConnectorConfig config,
                                               List<MetricDefinition> definitions,
                                               List<String> groupBy) {
        if (groupBy == null || groupBy.isEmpty()) {
            throw new IllegalArgumentException("group-by requires at least one column");
        }
        String table = resolvedTable(config);
        try (Connection connection = connect()) {
            String sql = buildGroupedSql(table, definitions, groupBy,
                    columnTypes(connection, table));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
            List<GroupedMetrics> groups = new ArrayList<>();
            while (result.next()) {
                List<String> display = new ArrayList<>();
                StringBuilder key = new StringBuilder();
                for (int index = 1; index <= groupBy.size(); index++) {
                    Object value = result.getObject(index);
                    display.add(value == null ? "-" : String.valueOf(value));
                    key.append(groupKey(value)).append('\u0001');
                }
                List<BigDecimal> values = new ArrayList<>();
                for (int index = 0; index < definitions.size(); index++) {
                    values.add(decimal(result.getObject(groupBy.size() + index + 1),
                            definitions.get(index)));
                }
                groups.add(new GroupedMetrics(key.toString(), String.join(" | ", display), values));
            }
                return groups;
            } catch (SQLException exception) {
                throw new IllegalArgumentException("DuckDB grouped metric query failed: "
                        + DuckDbErrors.message(exception) + " (sql=" + sql + ")", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to close DuckDB connection", exception);
        }
    }

    private static Connection connect() {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("duckdb metric engine requires org.duckdb:duckdb_jdbc", error);
        }
        try {
            return DriverManager.getConnection("jdbc:duckdb:");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to open DuckDB: " + DuckDbErrors.message(exception), exception);
        }
    }

    private static String resolvedTable(ConnectorConfig config) {
        return com.consilens.cli.service.DuckDbFileSql.tableExpression(config);
    }

    static boolean supports(ConnectorConfig config) {
        if (config == null || !"file".equalsIgnoreCase(config.getType())
                || config.getResource() == null) {
            return false;
        }
        String location = config.getResource().getPath() != null
                && !config.getResource().getPath().isBlank()
                ? config.getResource().getPath() : config.getResource().getName();
        return location != null && !location.isBlank()
                && !location.toLowerCase(Locale.ROOT).startsWith("s3://")
                && !location.toLowerCase(Locale.ROOT).startsWith("s3a://");
    }

    private static String buildSql(String table, List<MetricDefinition> definitions,
                                   Map<String, String> columnTypes) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int index = 0; index < definitions.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            MetricDefinition definition = definitions.get(index);
            sql.append(expression(definition, columnTypes)).append(" AS c").append(index + 1);
        }
        return sql.append(" FROM ").append(table).toString();
    }

    private static String buildGroupedSql(String table, List<MetricDefinition> definitions,
                                          List<String> groupBy, Map<String, String> columnTypes) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int index = 0; index < groupBy.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(identifier(groupBy.get(index))).append(" AS g").append(index + 1);
        }
        for (int index = 0; index < definitions.size(); index++) {
            sql.append(", ").append(expression(definitions.get(index), columnTypes))
                    .append(" AS c").append(index + 1);
        }
        sql.append(" FROM ").append(table).append(" GROUP BY ");
        for (int index = 0; index < groupBy.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(index + 1);
        }
        sql.append(" ORDER BY ");
        for (int index = 0; index < groupBy.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(index + 1);
        }
        return sql.toString();
    }

    private static String groupKey(Object value) {
        if (value == null) {
            return "\u0000null";
        }
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static String expression(MetricDefinition definition, Map<String, String> columnTypes) {
        String column = definition.getColumn() == null ? null : identifier(definition.getColumn());
        switch (definition.getKind()) {
            case ROW_COUNT:
                return "COUNT(*)";
            case NON_NULL_COUNT:
                return "COUNT(" + column + ")";
            case NULL_COUNT:
                return "(COUNT(*) - COUNT(" + column + "))";
            case SUM:
                return "SUM(" + numeric(definition.getColumn(), column, columnTypes) + ")";
            case MIN:
                return "MIN(" + numeric(definition.getColumn(), column, columnTypes) + ")";
            case MAX:
                return "MAX(" + numeric(definition.getColumn(), column, columnTypes) + ")";
            case AVG:
                return "AVG(" + numeric(definition.getColumn(), column, columnTypes) + ")";
            default:
                throw new IllegalArgumentException("Unsupported metric kind: " + definition.getKind());
        }
    }

    /**
     * Numeric aggregates need an explicit cast only when the column is not
     * numeric: an all-NULL column (or an empty file) is inferred as VARCHAR and
     * SUM/AVG would fail with a binder error instead of returning NULL like the
     * Java scanner path.
     *
     * <p>数值列本身不加 cast：{@code DECIMAL(38,15)} 会把超过 15 位小数的值
     * 静默四舍五入，可能把真实差异抹平成 PASS。
     */
    private static String numeric(String name, String column, Map<String, String> columnTypes) {
        String type = name == null ? null : columnTypes.get(name.toLowerCase(Locale.ROOT));
        return isNumericType(type) ? column : "CAST(" + column + " AS DECIMAL(38,15))";
    }

    private static boolean isNumericType(String type) {
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase(Locale.ROOT);
        return upper.contains("INT") || upper.contains("DECIMAL") || upper.contains("NUMERIC")
                || upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("REAL");
    }

    /** DESCRIBE 探测列类型（一次查询，取不到时按未知处理，回退到保守的 cast）。 */
    private static Map<String, String> columnTypes(Connection connection, String table) {
        Map<String, String> types = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM " + table)) {
            while (result.next()) {
                String name = result.getString(1);
                String type = result.getString(2);
                if (name != null && type != null) {
                    types.put(name.toLowerCase(Locale.ROOT), type);
                }
            }
        } catch (SQLException exception) {
            return types;
        }
        return types;
    }

    private static String identifier(String column) {
        return "\"" + column.replace("\"", "\"\"") + "\"";
    }

    private static BigDecimal decimal(Object value, MetricDefinition definition) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal;
        try {
            if (value instanceof BigDecimal) {
                decimal = (BigDecimal) value;
            } else if (value instanceof Number) {
                decimal = new BigDecimal(value.toString());
            } else {
                decimal = new BigDecimal(String.valueOf(value));
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Metric " + definition.label()
                    + " returned a non-numeric value: " + value, exception);
        }
        // Keep aggregates identical to the Java scanner path: DuckDB SUM over
        // DOUBLE exposes binary artifacts (0.30000000000000004) that the
        // BigDecimal-based Java path does not. 15 significant digits matches
        // the precision of a double.
        if ((definition.getKind() == MetricDefinition.Kind.SUM
                || definition.getKind() == MetricDefinition.Kind.AVG)
                && (value instanceof Double || value instanceof Float)) {
            decimal = decimal.round(new MathContext(15, RoundingMode.HALF_UP));
        }
        return definition.getKind() == MetricDefinition.Kind.AVG
                ? decimal.setScale(6, RoundingMode.HALF_UP) : decimal;
    }

    static final class GroupedMetrics {

        private final String key;
        private final String display;
        private final List<BigDecimal> values;

        private GroupedMetrics(String key, String display, List<BigDecimal> values) {
            this.key = key;
            this.display = display;
            this.values = values;
        }

        String key() {
            return key;
        }

        String display() {
            return display;
        }

        List<BigDecimal> values() {
            return values;
        }
    }
}
