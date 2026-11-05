package com.consilens.connector.oceanbase;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;
import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * OceanBase SQL query generator.
 *
 * <p>
 * Implements OceanBase-specific SQL syntax.
 * OceanBase in MySQL mode is highly compatible with MySQL, so this generator
 * shares much logic with MySQLSqlQueryGenerator with the following differences:
 * <ul>
 * <li>FULL OUTER JOIN is natively supported (no UNION simulation needed)</li>
 * <li>LIMIT syntax: LIMIT offset, limit (MySQL style)</li>
 * <li>CHECKSUM: MD5(GROUP_CONCAT(...))</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class OceanBaseSqlQueryGenerator extends BaseSqlQueryGenerator {

    @Override
    public boolean backslashEscapesStringLiterals() {
        return true;
    }

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public OceanBaseSqlQueryGenerator(CapabilityProvider capabilityProvider,
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
    public String getLimitClause(long offset, long limit) {
        // OceanBase MySQL mode uses MySQL syntax: LIMIT offset, limit
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
        } else {
            return getChecksumSQLWithConcat(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
        }
    }
    @Override
    protected String buildNullSafeNotEquals(String left, String right) {
        return "NOT (" + left + " <=> " + right + ")";
    }

    /**
     * Generate checksum SQL using traditional CONCAT method (backward compatible)
     */
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
            // Use primary key columns for ordering to ensure stable sort
            sql.append("COALESCE(MD5(GROUP_CONCAT(row_checksum ORDER BY pk_key, row_checksum SEPARATOR '|')), '') as checksum ");
            sql.append("FROM (");
            sql.append("SELECT ");

            // Build the primary key for stable ordering
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

            // Build the row checksum using all columns
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

            sql.append(") AS data");
        }

        log.debug("Generated CONCAT checksum SQL for table: {}", tableName);
        return sql.toString();
    }

    /**
     * Generate checksum SQL using XOR method (high performance)
     */
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
            // Optimized XOR: Calculate MD5 once per row (all columns concatenated)
            // Then use BIT_XOR to aggregate - no ORDER BY needed (XOR is commutative)
            sql.append("LPAD(HEX(CAST(BIT_XOR(CAST(CONV(SUBSTRING(MD5(CONCAT_WS('|', ");

            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }

                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                String normalizedCol = escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType));

                sql.append(normalizedCol);
            }

            sql.append(")), 1, 16), 16, 10) AS SIGNED)) AS SIGNED)), 16, '0') as checksum ");
        }

        sql.append("FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        log.debug("Generated XOR checksum SQL for table: {}", tableName);
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
    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        // SELECT primary key columns
        sql.append("SELECT ");
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        // Add row_hash column using MD5(CONCAT_WS('|', ...))
        sql.append(", MD5(CONCAT_WS('|'");

        // Add normalized columns using type-specific normalization
        for (String col : columns) {
            sql.append(", ");
            DataType dataType = columnDataTypes.get(col);
            sql.append(escapeHashValue(dataTypeHandler.normalizeColumn(col, dataType)));
        }

        sql.append(")) AS row_hash");

        // FROM clause
        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        // WHERE clause (if provided)
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        log.debug("Generated row-hash SQL for table: {}", tableName);

        return sql.toString();
    }
}
