package com.consilens.connector.clickhouse;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse SQL query generator.
 * 
 * <p>
 * Implements ClickHouse-specific SQL syntax:
 * <ul>
 * <li>LIMIT syntax: LIMIT x OFFSET y</li>
 * <li>CHECKSUM: MD5(STRING_AGG(...))</li>
 * <li>FULL OUTER JOIN: native support!</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class ClickHouseSqlQueryGenerator extends BaseSqlQueryGenerator {

    @Override
    public boolean backslashEscapesStringLiterals() {
        return true;
    }

    /**
     * ClickHouse 不支持常规关联子查询（correlated subquery 需实验开关），而键多重性过滤器
     * （{@code EXISTS (SELECT 1 FROM t WHERE t.k = outer.k GROUP BY ... HAVING COUNT(*) > 1)}）
     * 依赖它：子查询里的外层列会被当成自身列/常量解析，导致重复键集合判别错误。
     *
     * <p>真机表现：`clickhouse-dqcmp-09-join-edge-dup` 期望 {@code (6,3,1,2)}，实际
     * {@code (11,6,3,2)}——重复键被重复计入 presence。与其他不支持的方言（Doris/Trino）一致，
     * 这里退化为**整表本地多重集配对**：结果与 checksum/streaming 路径一致，只是少了 SQL 侧过滤。
     */
    @Override
    public boolean supportsKeyMultiplicityFilter() {
        return false;
    }

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public ClickHouseSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }


    @Override
    protected String joinSettingsClause() {
        // ClickHouse 默认 join_use_nulls=0：外连接未匹配侧填列默认值而非 NULL，
        // presence 归因（IS NULL）会全部失效 → 逐条设置开启。
        return " SETTINGS join_use_nulls = 1";
    }

    /** ClickHouse 字符串化排序（键可能是 Nullable）。 */
    @Override
    protected String orderByExpression(String column) {
        return "CAST(" + capabilityProvider.quote(column) + " AS Nullable(String)) NULLS FIRST";
    }

    /** join 计划比较列的类型归一化处理器（与 checksum 路径同口径）。 */
    @Override
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return this.dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        // ClickHouse syntax: LIMIT offset, limit (MySQL-style)
        if (offset <= 0) {
            return "LIMIT " + limit;
        }
        return "LIMIT " + offset + ", " + limit;
    }

    @Override
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            // ClickHouse does not support:
            //   - CONCAT_WS (use concat instead)
            //   - ORDER BY inside aggregate functions like MySQL's GROUP_CONCAT
            // Use nested subquery approach: ORDER BY in inner subquery, then groupArray preserves order.
            // groupArray collects values in row order, so ORDER BY in subquery ensures correct ordering.
            sql.append("COALESCE(lower(hex(MD5(arrayStringConcat(groupArray(row_checksum), '|')))), '') as checksum ");
            sql.append("FROM (");
            sql.append("SELECT row_checksum FROM (");
            sql.append("SELECT ");

            // Build primary key for stable ordering using concat (ClickHouse has no CONCAT_WS)
            // Note: ClickHouse concat() requires String arguments, so wrap with toString()
            if (keyColumns.size() == 1) {
                String col = keyColumns.get(0);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue("COALESCE(toString(" + formatForChecksum(col, dataType) + "), '')"))
                        .append(" as pk_key, ");
            } else {
                sql.append("concat(");
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (i > 0) {
                        sql.append(", '|', ");
                    }
                    String col = keyColumns.get(i);
                    DataType dataType = columnDataTypes.get(col);
                    sql.append(escapeHashValue("COALESCE(toString(" + formatForChecksum(col, dataType) + "), '')"));
                }
                sql.append(") as pk_key, ");
            }

            // Build per-row checksum using MD5 (lower(hex()) to match MySQL's lowercase hex output)
            // Wrap with toString() because ClickHouse concat() requires String arguments
            sql.append("lower(hex(MD5(concat(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", '|', ");
                }
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue("COALESCE(toString(" + formatForChecksum(col, dataType) + "), '')"));
            }
            sql.append(")))) as row_checksum ");

            sql.append("FROM ");
            sql.append(buildRelationRef(schemaName, tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            // Order by pk_key to ensure deterministic ordering. The huge LIMIT keeps
            // the ORDER BY effective (ClickHouse may otherwise optimize it away) while
            // never truncating a checksum for tables with more than ten million rows.
            sql.append(" ORDER BY pk_key, row_checksum LIMIT 18446744073709551615");
            sql.append(") AS ordered");
            sql.append(") AS data");
        }

        return sql.toString();
    }

    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT ");
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        // lower(hex(MD5(...))) to match MySQL's MD5 lowercase hex output
        // Use concat() with toString() because ClickHouse CONCAT_WS doesn't exist
        // and concat() requires String arguments
        sql.append(", lower(hex(MD5(concat(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", '|', ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            sql.append(escapeHashValue("COALESCE(toString(" + formatForChecksum(col, dataType) + "), '')"));
        }
        sql.append(")))) AS row_hash");

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }
    @Override
    public String getBatchInsertSQL(String tableName, List<String> columns, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");

        // Add quoted column names
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(columns.get(i)));
        }
        sql.append(") VALUES ");

        // Create placeholder for one row
        String valuePlaceholder = "(" +
                String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";

        // Repeat for batch size
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(valuePlaceholder);
        }

        return sql.toString();
    }
    /**
     * ClickHouse 的 replaceAll 以 RE2 正则解释 pattern，{@code '|'} 必须转义成 {@code '\\|'}，
     * 否则会匹配空串并把每个字符之间都插入分隔符。
     */
    @Override
    protected String escapeHashValue(String normalizedExpr) {
        return "replaceAll(" + normalizedExpr + ", '\\\\|', '||')";
    }

    /**
     * Format a column for checksum calculation, applying type-specific formatting
     * for temporal types (Date, DateTime, Timestamp).
     *
     * <p>normalizeDate/normalizeDateTime etc. now return the raw column to avoid
     * type mismatches in WHERE clauses. This method re-applies the proper formatting
     * when the column is used inside the checksum concat().
     */
    private String formatForChecksum(String quotedCol, DataType dataType) {
        if (dataType == null) {
            return dataTypeHandler.normalizeColumn(quotedCol, null);
        }
        String typeName = dataType.name().toLowerCase();
        if (dataTypeHandler instanceof ClickHouseDataTypeHandler) {
            ClickHouseDataTypeHandler chHandler = (ClickHouseDataTypeHandler) dataTypeHandler;
            if (typeName.contains("date") && !typeName.contains("time")) {
                return chHandler.formatDateForChecksum(quotedCol);
            }
            if (typeName.contains("time") || typeName.contains("datetime") || typeName.contains("timestamp")) {
                return chHandler.formatDateTimeForChecksum(quotedCol, typeName);
            }
        }
        return dataTypeHandler.normalizeColumn(quotedCol, dataType);
    }
}
