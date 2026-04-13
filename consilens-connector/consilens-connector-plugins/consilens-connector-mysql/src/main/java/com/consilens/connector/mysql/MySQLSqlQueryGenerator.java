package com.consilens.connector.mysql;

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
 * MySQL SQL query generator.
 * 
 * <p>
 * Implements MySQL-specific SQL syntax:
 * <ul>
 * <li>LIMIT syntax: LIMIT offset, limit</li>
 * <li>CHECKSUM: MD5(GROUP_CONCAT(...))</li>
 * <li>FULL OUTER JOIN: simulated using UNION</li>
 * </ul>
 * 
 * @since 1.0.0
 */
@Slf4j
public class MySQLSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public MySQLSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        // MySQL special syntax: LIMIT offset, limit
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
        
        if (checksumAlgorithm.isXor()) {
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
        
        // Note: group_concat_max_len is set to 1GB at connection initialization
        // (see MySQLConnectionPoolOptimizer.sessionVariables)
        // This prevents GROUP_CONCAT truncation which would cause false negatives in diff detection
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            // BUGFIX: Use only primary key columns for ordering to ensure stable sort
            // This prevents false checksum mismatches when data columns differ
            sql.append("COALESCE(MD5(GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')), '') as checksum ");
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
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
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
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append(")) as row_checksum ");

            sql.append("FROM ");
            if (schemaName != null && !schemaName.isEmpty()) {
                sql.append(capabilityProvider.quote(schemaName)).append(".");
            }
            sql.append(capabilityProvider.quote(tableName));

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
     * Formula: MD5(col1+'_C1') ^ MD5(col2+'_C2') ^ MD5(col3+'_C3') ^ ...
     * Note: Position sensitivity is achieved through unique salt per column (_C1, _C2, _C3...)
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
            // Performance: O(n) instead of O(n×m), no derived table materialization
            sql.append("LPAD(HEX(CAST(BIT_XOR(CAST(CONV(SUBSTRING(MD5(CONCAT_WS('|', ");
            
            // Build concatenated expression for all columns
            // Note: CONCAT_WS with '|' separator is already position-sensitive, no need for column salt
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);
                
                sql.append(normalizedCol);
            }
            
            sql.append(")), 1, 16), 16, 10) AS SIGNED)) AS SIGNED)), 16, '0') as checksum ");
        }

        sql.append("FROM ");
        if (schemaName != null && !schemaName.isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        log.debug("Generated XOR checksum SQL for table: {}", tableName);
        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2,
            List<String> joinColumns,
            String whereClause) {
        // MySQL doesn't support FULL OUTER JOIN, simulate using UNION
        StringBuilder sql = new StringBuilder();

        sql.append("(SELECT * FROM ").append(capabilityProvider.quote(table1))
                .append(" LEFT JOIN ").append(capabilityProvider.quote(table2))
                .append(" ON ").append(buildJoinCondition(table1, table2, joinColumns))
                .append(") UNION (SELECT * FROM ").append(capabilityProvider.quote(table1))
                .append(" RIGHT JOIN ").append(capabilityProvider.quote(table2))
                .append(" ON ").append(buildJoinCondition(table1, table2, joinColumns))
                .append(" WHERE ");

        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(capabilityProvider.quote(table1)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" IS NULL");
        }
        sql.append(")");

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.insert(0, "SELECT * FROM (");
            sql.append(") AS full_join WHERE ").append(whereClause);
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
     * Build join condition for the given columns.
     */
    private String buildJoinCondition(String table1, String table2, List<String> joinColumns) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0) {
                condition.append(" AND ");
            }
            condition.append(capabilityProvider.quote(table1)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" = ")
                    .append(capabilityProvider.quote(table2)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)));
        }
        return condition.toString();
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
        // Use the same normalization approach as getChecksumSQL for consistency
        // NOTE: We don't include normalized column values here to reduce network transfer
        // When row_hash mismatch is detected, we'll query the normalized values separately
        sql.append(", MD5(CONCAT_WS('|'");

        // Add normalized columns using type-specific normalization
        for (String col : columns) {
            sql.append(", ");
            DataType dataType = columnDataTypes.get(col);
            sql.append(dataTypeHandler.normalizeColumn(col, dataType));
        }

        sql.append(")) AS row_hash");

        // FROM clause
        sql.append(" FROM ");
        if (schemaName != null && !schemaName.isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        // WHERE clause (if provided)
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        // No ORDER BY - sequential scan for performance

        log.debug("Generated row-hash SQL for table: {}", tableName);

        return sql.toString();
    }

}
