package com.consilens.connector.starrocks;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * StarRocks SQL query generator.
 * 
 * <p>
 * Implements StarRocks-specific SQL syntax:
 * <ul>
 * <li>LIMIT syntax: LIMIT offset, limit</li>
 * <li>CHECKSUM: MD5(GROUP_CONCAT(...))</li>
 * <li>FULL OUTER JOIN: simulated using UNION</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class StarRocksSqlQueryGenerator extends BaseSqlQueryGenerator {

    @Override
    public boolean backslashEscapesStringLiterals() {
        return true;
    }

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public StarRocksSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
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

    /** This dialect implements XOR checksum aggregation. */
    @Override
    protected boolean supportsXorChecksum() {
        return true;
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
        // StarRocks special syntax: LIMIT offset, limit
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
            String whereClause,
            ChecksumAlgorithm checksumAlgorithm) {
        if (checksumAlgorithm != null && checksumAlgorithm.isXor()) {
            return getChecksumSQLWithXor(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
        }
        return getChecksumSQLWithConcat(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
    }
    private String getChecksumSQLWithConcat(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            // Two-step approach matching MySQL: per-row MD5 + aggregate MD5
            sql.append("COALESCE(MD5(GROUP_CONCAT(row_checksum ORDER BY pk_key, row_checksum SEPARATOR '|')), '') as checksum ");
            sql.append("FROM (SELECT ");

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

            // Build per-row checksum using MD5
            sql.append("MD5(CONCAT_WS('|', ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(")) as row_checksum FROM ");

            sql.append(buildRelationRef(schemaName, tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            sql.append(") AS data");
        }

        return sql.toString();
    }

    private String getChecksumSQLWithXor(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'0' as checksum ");
        } else {
            sql.append("CASE WHEN COUNT(*) = 0 THEN '0' ELSE CONCAT(");
            for (int i = 1; i <= 16; i++) {
                if (i > 1) {
                    sql.append(", ");
                }
                sql.append(buildXorHexDigitExpression(i));
            }
            sql.append(") END as checksum ");
            sql.append("FROM (SELECT UPPER(SUBSTRING(MD5(CONCAT_WS('|', ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
            }
            sql.append(")), 1, 16)) as row_hash FROM ");
            sql.append(buildRelationRef(schemaName, tableName));
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }
            sql.append(") AS data");
        }

        return sql.toString();
    }

    private String buildXorHexDigitExpression(int position) {
        String nibbleValue = "CAST(CONV(SUBSTRING(row_hash, " + position + ", 1), 16, 10) AS BIGINT)";
        StringBuilder sql = new StringBuilder("UPPER(CONV(");
        int[] masks = {1, 2, 4, 8};
        for (int i = 0; i < masks.length; i++) {
            if (i > 0) {
                sql.append(" + ");
            }
            int mask = masks[i];
            sql.append("MOD(SUM(MOD(FLOOR(")
                    .append(nibbleValue)
                    .append(" / ")
                    .append(mask)
                    .append("), 2)), 2) * ")
                    .append(mask);
        }
        sql.append(", 10, 16))");
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

        // Add primary key columns
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        // Add row_hash column using MD5 of all columns
        sql.append(", MD5(CONCAT_WS('|', ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
        }
        sql.append(")) as row_hash FROM ");

        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        // Order by primary keys for consistent output
        sql.append(" ORDER BY ");
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
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
