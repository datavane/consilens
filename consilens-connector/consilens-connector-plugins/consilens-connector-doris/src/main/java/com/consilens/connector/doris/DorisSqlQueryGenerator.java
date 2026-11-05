package com.consilens.connector.doris;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Doris SQL query generator.
 * 
 * <p>
 * Implements Doris-specific SQL syntax:
 * <ul>
 * <li>LIMIT syntax: LIMIT offset, limit</li>
 * <li>CHECKSUM: MD5(GROUP_CONCAT(...))</li>
 * <li>FULL OUTER JOIN: simulated using UNION</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class DorisSqlQueryGenerator extends BaseSqlQueryGenerator {

    /**
     * 派生表里的 ORDER BY 必须带 LIMIT，否则 MySQL 系优化器会丢弃排序，GROUP_CONCAT 顺序不稳定，
     * checksum 会随机变化。
     *
     * <p>Doris 解析 LIMIT 参数时按有符号 BIGINT 处理，沿用 MySQL 的 ULONG 上限
     * （18446744073709551615）会直接抛
     * {@code NumberFormatException: For input string: "18446744073709551615"}，
     * 因此这里改用 BIGINT 上限，语义上同样是“不设上限”。
     */
    private static final String UNBOUNDED_LIMIT = "9223372036854775807";

    @Override
    public boolean backslashEscapesStringLiterals() {
        return true;
    }

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public DorisSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }


    @Override
    public boolean supportsKeyMultiplicityFilter() {
        // Doris 报 "Unsupported correlated EXISTS subquery with a HAVING clause"：
        // 开放档 join 遇重复键时退化为整表本地多重集配对（不 N×M，结果与 checksum 一致）。
        return false;
    }

    /** MySQL 族没有 VARCHAR 类型，字符串化排序用 CHAR（与 MySQL 生成器一致）。 */
    @Override
    protected String orderByExpression(String column) {
        return "CAST(" + capabilityProvider.quote(column) + " AS CHAR)";
    }

    /** join 计划比较列的类型归一化处理器（与 checksum 路径同口径）。 */
    @Override
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return this.dataTypeHandler;
    }

    /** 归一化结果为字符串时，须做字节级比较以规避库内排序规则（如 utf8mb4_0900_ai_ci）。 */
    @Override
    protected String binarySafeExpression(String expression) {
        return "HEX(" + expression + ")";
    }

    @Override
    protected String buildNullSafeNotEquals(String left, String right) {
        return "NOT (" + left + " <=> " + right + ")";
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        // Doris special syntax: LIMIT offset, limit
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
            // Doris GROUP_CONCAT syntax: GROUP_CONCAT(expr, separator) with comma
            // ORDER BY must be in a subquery since Doris doesn't support ORDER BY inside GROUP_CONCAT
            sql.append("COALESCE(MD5(GROUP_CONCAT(row_checksum, '|')), '') as checksum ");
            sql.append("FROM (");
            sql.append("SELECT row_checksum FROM (");
            sql.append("SELECT ");

            // Build primary key for stable ordering
            sql.append("CONCAT_WS('|', ");
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = keyColumns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(") as pk_key, ");

            // Build per-row checksum using MD5 (already STRING type)
            sql.append("MD5(CONCAT_WS('|', ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(")) as row_checksum ");

            sql.append("FROM ");
            sql.append(buildRelationRef(schemaName, tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            // Order by pk_key to ensure deterministic checksum。
            // 派生表里的 ORDER BY 没有 LIMIT 会被 MySQL 系优化器直接丢弃，GROUP_CONCAT
            // 的顺序就不再保证，checksum 会随机变化；Doris 不接受 ULONG 上限，用 UNBOUNDED_LIMIT。
            sql.append(" ORDER BY pk_key, row_checksum LIMIT ").append(UNBOUNDED_LIMIT);
            sql.append(") AS ordered_data) AS data");
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

        sql.append(", MD5(CONCAT_WS('|'");

        for (String col : columns) {
            sql.append(", ");
            DataType dataType = columnDataTypes.get(col);
            sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
        }

        sql.append(")) AS row_hash");

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
}
