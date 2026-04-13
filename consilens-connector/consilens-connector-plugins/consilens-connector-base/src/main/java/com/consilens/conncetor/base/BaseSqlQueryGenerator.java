package com.consilens.conncetor.base;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.model.DataType;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public abstract class BaseSqlQueryGenerator implements SqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public BaseSqlQueryGenerator(CapabilityProvider capabilityProvider) {
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getLimitClause(long limit) {
        return "LIMIT " + limit;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return getLimitClause(limit);
        }
        // Default implementation for databases that support LIMIT with OFFSET
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String getCountSQL(String schemaName, String tableName, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ");

        // Only add schema if it's not null and not empty
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }

        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getSelectSQL(String schemaName, String tableName,
            List<String> selectExpressions,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        }

        return sql.toString();
    }

    @Override
    public String getSelectByKeysSQL(String schemaName, String tableName,
            List<String> selectExpressions,
            List<String> keyColumns,
            List<List<Object>> primaryKeys,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        String keyPredicate = buildKeyPredicate(keyColumns, primaryKeys);
        boolean hasKeyPredicate = keyPredicate != null && !keyPredicate.isEmpty();
        boolean hasWhereClause = whereClause != null && !whereClause.trim().isEmpty();

        if (hasKeyPredicate) {
            sql.append(" WHERE ").append(keyPredicate);
            if (hasWhereClause) {
                sql.append(" AND (").append(whereClause).append(")");
            }
        } else if (hasWhereClause) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        }

        return sql.toString();
    }

    @Override
    public String getMinMaxKeySQL(String schemaName, String tableName,
            List<String> keyColumns,
            boolean getMin,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String column = capabilityProvider.quote(keyColumns.get(i));
            sql.append(getMin ? "MIN(" : "MAX(").append(column).append(")");
        }
        sql.append(" FROM ");
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getDistinctCountSQL(String schemaName, String tableName, List<String> columns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT ");
        sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        sql.append(") FROM ");
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        if (!capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            // Fallback to left and right outer joins unioned
            String leftJoin = getLeftOuterJoinSQL(table1, table2, joinColumns, whereClause);
            String rightJoin = getRightOuterJoinSQL(table1, table2, joinColumns, whereClause);
            return "(" + leftJoin + ") UNION (" + rightJoin + ")";
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" FULL OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getLeftOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" LEFT OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getRightOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" RIGHT OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getCreateTempTableSQL(String tempTableName, String selectSQL) {
        return "CREATE TEMPORARY TABLE " + capabilityProvider.quote(tempTableName) + " AS " + selectSQL;
    }

    @Override
    public String getDropTableSQL(String tableName, boolean ifExists) {
        return "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + capabilityProvider.quote(tableName);
    }

    @Override
    public String getInsertSQL(String tableName, List<String> columns) {
        return "INSERT INTO " + capabilityProvider.quote(tableName) +
                " (" + String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)) + ")";
    }

    @Override
    public String getBatchInsertSQL(String tableName, List<String> columns, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive, got: " + batchSize);
        }

        if (batchSize == 1) {
            // Single row: INSERT INTO table (col1, col2) VALUES (?, ?)
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");
            sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
            sql.append(") VALUES (");
            sql.append(String.join(", ", IntStream.range(0, columns.size())
                    .mapToObj(i -> "?").toArray(String[]::new)));
            sql.append(")");
            return sql.toString();
        }

        // Multiple rows: INSERT INTO table (col1, col2) VALUES (?, ?), (?, ?), ...
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");
        sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        sql.append(") VALUES ");

        // Build (?, ?, ...) placeholder
        String valuePlaceholder = "(" +
                String.join(", ", IntStream.range(0, columns.size())
                        .mapToObj(i -> "?").toArray(String[]::new))
                + ")";

        // Repeat placeholder batchSize times, separated by commas
        sql.append(String.join(", ", IntStream.range(0, batchSize)
                .mapToObj(i -> valuePlaceholder).toArray(String[]::new)));

        return sql.toString();
    }

    @Override
    public String formatValue(Object value) {
        return SqlQueryGenerator.super.formatValue(value);
    }


    @Override
    public String getJoinDiffStatsSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, String where1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, String where2) {
        String t1 = buildTableSource(schema1, table1, alias1, where1);
        String t2 = buildTableSource(schema2, table2, alias2, where2);
        String joinCondition = buildJoinCondition(alias1, keyColumns1, alias2, keyColumns2);
        String t1KeysNull = buildKeysNullExpression(alias1, keyColumns1);
        String t2KeysNull = buildKeysNullExpression(alias2, keyColumns2);
        String diffPredicate = buildDiffPredicate(alias1, alias2, compareColumns1, compareColumns2);

        if (capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ");
            sql.append("SUM(CASE WHEN ").append(t1KeysNull).append(" THEN 0 ELSE 1 END) AS source_count, ");
            sql.append("SUM(CASE WHEN ").append(t2KeysNull).append(" THEN 0 ELSE 1 END) AS target_count, ");
            sql.append("SUM(CASE WHEN ").append(t1KeysNull).append(" THEN 1 ELSE 0 END) AS source_missing, ");
            sql.append("SUM(CASE WHEN ").append(t2KeysNull).append(" THEN 1 ELSE 0 END) AS target_missing, ");
            sql.append("SUM(CASE WHEN NOT ").append(t1KeysNull).append(" AND NOT ").append(t2KeysNull)
                    .append(" AND ").append(diffPredicate).append(" THEN 1 ELSE 0 END) AS mismatch ");
            sql.append("FROM ").append(t1).append(" FULL OUTER JOIN ").append(t2);
            sql.append(" ON ").append(joinCondition);
            return sql.toString();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT SUM(source_count) AS source_count, ");
        sql.append("SUM(target_count) AS target_count, ");
        sql.append("SUM(source_missing) AS source_missing, ");
        sql.append("SUM(target_missing) AS target_missing, ");
        sql.append("SUM(mismatch) AS mismatch ");
        sql.append("FROM (");

        sql.append("SELECT ");
        sql.append("SUM(CASE WHEN ").append(t1KeysNull).append(" THEN 0 ELSE 1 END) AS source_count, ");
        sql.append("SUM(CASE WHEN ").append(t2KeysNull).append(" THEN 0 ELSE 1 END) AS target_count, ");
        sql.append("SUM(CASE WHEN ").append(t1KeysNull).append(" THEN 1 ELSE 0 END) AS source_missing, ");
        sql.append("0 AS target_missing, ");
        sql.append("SUM(CASE WHEN NOT ").append(t1KeysNull).append(" AND NOT ").append(t2KeysNull)
                .append(" AND ").append(diffPredicate).append(" THEN 1 ELSE 0 END) AS mismatch ");
        sql.append("FROM ").append(t1).append(" LEFT OUTER JOIN ").append(t2);
        sql.append(" ON ").append(joinCondition);

        sql.append(" UNION ALL ");

        sql.append("SELECT ");
        sql.append("0 AS source_count, ");
        sql.append("SUM(CASE WHEN ").append(t2KeysNull).append(" THEN 0 ELSE 1 END) AS target_count, ");
        sql.append("0 AS source_missing, ");
        sql.append("SUM(CASE WHEN ").append(t2KeysNull).append(" THEN 1 ELSE 0 END) AS target_missing, ");
        sql.append("0 AS mismatch ");
        sql.append("FROM ").append(t2).append(" LEFT OUTER JOIN ").append(t1);
        sql.append(" ON ").append(joinCondition);

        sql.append(") s");
        return sql.toString();
    }

    @Override
    public String getJoinDiffDetailSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, List<String> outputColumns1, String where1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, List<String> outputColumns2, String where2) {
        String t1 = buildTableSource(schema1, table1, alias1, where1);
        String t2 = buildTableSource(schema2, table2, alias2, where2);
        String joinCondition = buildJoinCondition(alias1, keyColumns1, alias2, keyColumns2);
        String diffPredicate = buildDiffPredicate(alias1, alias2, compareColumns1, compareColumns2);
        String t1KeysNull = buildKeysNullExpression(alias1, keyColumns1);
        String t2KeysNull = buildKeysNullExpression(alias2, keyColumns2);

        if (capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            List<String> selectExprs = new java.util.ArrayList<>();
            selectExprs.add("CASE WHEN " + t1KeysNull + " THEN 'source_missing' " +
                    "WHEN " + t2KeysNull + " THEN 'target_missing' ELSE 'mismatch' END AS nl_dq_diff_type");
            selectExprs.add("CASE WHEN " + t1KeysNull + " OR " + t2KeysNull + " THEN NULL ELSE "
                    + buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2)
                    + " END AS nl_dq_diff_columns1");
            selectExprs.add("CASE WHEN " + t1KeysNull + " OR " + t2KeysNull + " THEN NULL ELSE "
                    + buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2)
                    + " END AS nl_dq_diff_columns2");
            selectExprs.addAll(buildOutputSelect(alias1, outputColumns1, "_1"));
            selectExprs.addAll(buildOutputSelect(alias2, outputColumns2, "_2"));

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ").append(String.join(", ", selectExprs));
            sql.append(" FROM ").append(t1).append(" FULL OUTER JOIN ").append(t2);
            sql.append(" ON ").append(joinCondition);
            sql.append(" WHERE ").append(t1KeysNull).append(" OR ").append(t2KeysNull)
                    .append(" OR ").append(diffPredicate);
            return sql.toString();
        }

        String mismatchSelect = buildMismatchSelect(t1, t2, alias1, alias2, compareColumns1, compareColumns2,
                outputColumns1, outputColumns2, diffPredicate, joinCondition);
        String sourceMissingSelect = buildMissingSelect("source_missing", t2, t1, alias2, alias1,
                outputColumns2, outputColumns1, t1KeysNull, joinCondition, false);
        String targetMissingSelect = buildMissingSelect("target_missing", t1, t2, alias1, alias2,
                outputColumns1, outputColumns2, t2KeysNull, joinCondition, true);

        return "(" + mismatchSelect + ") UNION ALL (" + sourceMissingSelect + ") UNION ALL (" + targetMissingSelect + ")";
    }

    private String buildTableRef(String schemaName, String tableName, String alias) {
        StringBuilder ref = new StringBuilder();
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            ref.append(capabilityProvider.quote(schemaName)).append(".");
        }
        ref.append(capabilityProvider.quote(tableName));
        if (alias != null && !alias.trim().isEmpty()) {
            ref.append(" ").append(alias);
        }
        return ref.toString();
    }

    private String columnRef(String alias, String column) {
        if (alias == null || alias.trim().isEmpty()) {
            return capabilityProvider.quote(column);
        }
        return alias + "." + capabilityProvider.quote(column);
    }

    private String buildJoinCondition(String alias1, List<String> keyColumns1,
            String alias2, List<String> keyColumns2) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < keyColumns1.size(); i++) {
            if (i > 0) {
                condition.append(" AND ");
            }
            String col1 = keyColumns1.get(i);
            String col2 = keyColumns2.size() > i ? keyColumns2.get(i) : keyColumns1.get(i);
            condition.append(columnRef(alias1, col1))
                    .append(" = ")
                    .append(columnRef(alias2, col2));
        }
        return condition.toString();
    }

    private String buildKeysNullExpression(String alias, List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            return "1=0";
        }
        StringBuilder expr = new StringBuilder();
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                expr.append(" AND ");
            }
            expr.append(columnRef(alias, keyColumns.get(i))).append(" IS NULL");
        }
        return "(" + expr + ")";
    }

    protected String buildNullSafeNotEquals(String left, String right) {
        return "((" + left + " IS NOT NULL AND " + right + " IS NOT NULL AND " + left + " <> " + right + ")"
                + " OR (" + left + " IS NULL AND " + right + " IS NOT NULL)"
                + " OR (" + left + " IS NOT NULL AND " + right + " IS NULL))";
    }

    protected String buildDiffColumnsExpression(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2) {
        if (compareColumns1 == null || compareColumns1.isEmpty()) {
            return "'[]'";
        }
        StringBuilder sql = new StringBuilder();
        sql.append("CONCAT('[', CONCAT_WS(',', ");
        int count = Math.min(compareColumns1.size(),
                compareColumns2 != null ? compareColumns2.size() : compareColumns1.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String col1 = compareColumns1.get(i);
            String col2 = compareColumns2 != null && compareColumns2.size() > i ? compareColumns2.get(i) : col1;
            String c1 = columnRef(alias1, col1);
            String c2 = columnRef(alias2, col2);
            sql.append("CASE WHEN ").append(buildNullSafeNotEquals(c1, c2))
                    .append(" THEN '\"").append(col1).append("\"' ELSE NULL END");
        }
        sql.append("), ']')");
        return sql.toString();
    }

    protected String buildDiffPredicate(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2) {
        if (compareColumns1 == null || compareColumns1.isEmpty()) {
            return "1=0";
        }
        int count = Math.min(compareColumns1.size(),
                compareColumns2 != null ? compareColumns2.size() : compareColumns1.size());
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                predicate.append(" OR ");
            }
            String col1 = compareColumns1.get(i);
            String col2 = compareColumns2 != null && compareColumns2.size() > i ? compareColumns2.get(i) : col1;
            String c1 = columnRef(alias1, col1);
            String c2 = columnRef(alias2, col2);
            predicate.append(buildNullSafeNotEquals(c1, c2));
        }
        return "(" + predicate + ")";
    }

    protected List<String> buildOutputSelect(String alias, List<String> outputColumns, String suffix) {
        List<String> selectExprs = new java.util.ArrayList<>();
        if (outputColumns == null) {
            return selectExprs;
        }
        for (String col : outputColumns) {
            selectExprs.add(columnRef(alias, col) + " AS " + col + suffix);
        }
        return selectExprs;
    }

    private String buildMismatchSelect(String t1, String t2, String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            List<String> outputColumns1, List<String> outputColumns2,
            String diffPredicate, String joinCondition) {
        List<String> selectExprs = new java.util.ArrayList<>();
        selectExprs.add("'" + "mismatch" + "' AS nl_dq_diff_type");
        selectExprs.add(buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2)
                + " AS nl_dq_diff_columns1");
        selectExprs.add(buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2)
                + " AS nl_dq_diff_columns2");
        selectExprs.addAll(buildOutputSelect(alias1, outputColumns1, "_1"));
        selectExprs.addAll(buildOutputSelect(alias2, outputColumns2, "_2"));

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExprs));
        sql.append(" FROM ").append(t1).append(" INNER JOIN ").append(t2);
        sql.append(" ON ").append(joinCondition);
        sql.append(" WHERE ").append(diffPredicate);
        return sql.toString();
    }

    private String buildMissingSelect(String diffType, String primaryTable, String otherTable,
            String primaryAlias, String otherAlias,
            List<String> primaryColumns, List<String> otherColumns,
            String otherKeysNull, String joinCondition, boolean primaryIsTable1) {
        List<String> selectExprs = new java.util.ArrayList<>();
        selectExprs.add("'" + diffType + "' AS nl_dq_diff_type");
        selectExprs.add("NULL AS nl_dq_diff_columns1");
        selectExprs.add("NULL AS nl_dq_diff_columns2");
        if (primaryIsTable1) {
            selectExprs.addAll(buildOutputSelect(primaryAlias, primaryColumns, "_1"));
            for (String col : otherColumns) {
                selectExprs.add("NULL AS " + col + "_2");
            }
        } else {
            for (String col : otherColumns) {
                selectExprs.add("NULL AS " + col + "_1");
            }
            selectExprs.addAll(buildOutputSelect(primaryAlias, primaryColumns, "_2"));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExprs));
        sql.append(" FROM ").append(primaryTable).append(" LEFT OUTER JOIN ").append(otherTable);
        sql.append(" ON ").append(joinCondition);
        sql.append(" WHERE ").append(otherKeysNull);
        return sql.toString();
    }

    private String buildTableSource(String schemaName, String tableName, String alias, String whereClause) {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return buildTableRef(schemaName, tableName, alias);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("(SELECT * FROM ");
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));
        sql.append(" WHERE ").append(whereClause).append(") ").append(alias);
        return sql.toString();
    }

    /**
     * Build join condition for the given columns.
     */
    protected String buildJoinCondition(List<String> joinColumns) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0)
                condition.append(" AND ");
            condition.append("t1.").append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" = t2.").append(capabilityProvider.quote(joinColumns.get(i)));
        }
        return condition.toString();
    }

    /**
     * Build key predicate for primary key filtering.
     */
    protected String buildKeyPredicate(List<String> keyColumns, List<List<Object>> primaryKeys) {
        if (keyColumns == null || keyColumns.isEmpty() || primaryKeys == null || primaryKeys.isEmpty()) {
            return "";
        }

        if (keyColumns.size() == 1) {
            String column = capabilityProvider.quote(keyColumns.get(0));
            StringBuilder inClause = new StringBuilder();
            inClause.append(column).append(" IN (");
            String values = primaryKeys.stream()
                    .filter(pk -> pk != null && !pk.isEmpty())
                    .map(pk -> formatValue(pk.get(0)))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            inClause.append(values).append(")");
            return inClause.toString();
        }

        // Composite primary key - OR of ANDs
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            List<Object> pk = primaryKeys.get(i);
            if (pk == null || pk.size() != keyColumns.size()) {
                continue;
            }
            if (predicate.length() > 0) {
                predicate.append(" OR ");
            }
            StringBuilder andClause = new StringBuilder();
            for (int j = 0; j < keyColumns.size(); j++) {
                if (j > 0) {
                    andClause.append(" AND ");
                }
                andClause.append(capabilityProvider.quote(keyColumns.get(j)))
                        .append(" = ")
                        .append(formatValue(pk.get(j)));
            }
            predicate.append("(").append(andClause).append(")");
        }

        return predicate.toString();
    }

    // ========== Normalization Helper Methods ==========

    /**
     * Build a COALESCE expression with a default value.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression   the SQL expression to wrap
     * @param defaultValue the default value if expression is NULL (e.g., "''")
     * @return COALESCE SQL expression
     */
    protected String buildCoalesceExpression(String expression, String defaultValue) {
        return "COALESCE(" + expression + ", " + defaultValue + ")";
    }

    /**
     * Build a TRIM expression to remove leading and trailing whitespace.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression the SQL expression to trim
     * @return TRIM SQL expression
     */
    protected String buildTrimExpression(String expression) {
        return "TRIM(" + expression + ")";
    }

    /**
     * Build a CAST expression to convert a value to a target type.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression the SQL expression to cast
     * @param targetType the target SQL type (e.g., "TEXT", "VARCHAR(1000)")
     * @return CAST SQL expression
     */
    protected String buildCastExpression(String expression, String targetType) {
        return "CAST(" + expression + " AS " + targetType + ")";
    }

    /**
     * Build an expression to format decimal numbers with controlled precision.
     * This is useful for normalizing floating-point values to avoid precision
     * differences.
     * Subclasses should override this method to provide database-specific
     * formatting.
     *
     * @param expression    the SQL expression representing a decimal number
     * @param decimalPlaces number of decimal places to preserve (default: 4)
     * @return SQL expression that formats the decimal number
     */
    protected String buildFormatDecimalExpression(String expression, int decimalPlaces) {
        // Default implementation using standard SQL ROUND function
        return "ROUND(" + expression + ", " + decimalPlaces + ")";
    }

    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        // Default implementation - throws UnsupportedOperationException
        // Subclasses should override this method to provide database-specific implementation
        throw new UnsupportedOperationException(
                "getRowHashSQL is not implemented for " + this.getClass().getSimpleName() +
                ". Please implement this method to support row-hash local comparison.");
    }

    /**
     * Default implementation of getChecksumSQL with ChecksumAlgorithm parameter.
     * Subclasses should override this method to provide database-specific XOR implementation.
     * 
     * @param schemaName the schema name
     * @param tableName the table name
     * @param keyColumns the primary key columns
     * @param columns the columns to include in checksum
     * @param columnDataTypes mapping of column names to their data types
     * @param whereClause optional WHERE clause
     * @param checksumAlgorithm the checksum calculation algorithm
     * @return SQL checksum query
     */
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause,
            ChecksumAlgorithm checksumAlgorithm) {
        
        // For XOR algorithm, throw UnsupportedOperationException if not implemented by subclass
        if (checksumAlgorithm != null && checksumAlgorithm.isXor()) {
            throw new UnsupportedOperationException(
                "XOR checksum algorithm is not implemented for " + this.getClass().getSimpleName() + 
                ". Please implement getChecksumSQL with ChecksumAlgorithm parameter or use CONCAT algorithm.");
        }
        
        // For CONCAT algorithm or null, delegate to the original method
        return getChecksumSQL(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
    }
}
