package com.consilens.connector.sqlserver;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL Server SQL query generator.
 * Uses TOP for limiting results and OFFSET...FETCH for pagination.
 */
public class SQLServerSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public SQLServerSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return ""; // Use TOP in SELECT clause
        }
        // SQL Server 2012+ OFFSET...FETCH syntax
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * Generate SELECT with TOP for SQL Server.
     * Note: This is used when offset is 0.
     */
    public String getTopClause(long limit) {
        return "TOP " + limit;
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
            // Two-step approach matching MySQL: per-row MD5 + aggregate MD5
            sql.append("COALESCE(CONVERT(VARCHAR(MAX), HASHBYTES('MD5', ");
            sql.append("(SELECT STRING_AGG(row_checksum, '|') WITHIN GROUP (ORDER BY pk_key) FROM (SELECT ");

            // Build primary key for stable ordering
            sql.append("CONCAT_WS('|', ");
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0)
                    sql.append(", ");
                String col = keyColumns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append(") as pk_key, ");

            // Build per-row checksum using HASHBYTES MD5
            sql.append("CONVERT(VARCHAR(32), HASHBYTES('MD5', CONCAT_WS('|', ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    sql.append(", ");
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append(")), 2) as row_checksum");

            sql.append(" FROM ");
            sql.append(buildRelationRef(schemaName, tableName));
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            sql.append(") t)), 2), '') as checksum ");
        }

        sql.append("FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
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

        // SELECT primary key columns and row_hash
        sql.append("SELECT ");

        // Add primary key columns
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        sql.append(", CONVERT(VARCHAR(32), HASHBYTES('MD5', CONCAT(");

        // Build canonical expression using CHAR(31) as separator
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", CHAR(31), ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);

            // Use ISNULL to convert NULL to CHAR(1) (unified across all databases)
            sql.append("ISNULL(").append(normalizedCol).append(", CHAR(1))");
        }

        sql.append(")), 2) as row_hash");

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2,
            List<String> joinColumns,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT * FROM ").append(table1)
                .append(" FULL OUTER JOIN ").append(table2)
                .append(" ON ").append(buildJoinCondition(table1, table2, joinColumns));

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

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(capabilityProvider.quote(columns.get(i)));
        }
        sql.append(") VALUES ");

        String valuePlaceholder = "(" +
                String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";

        for (int i = 0; i < batchSize; i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(valuePlaceholder);
        }

        return sql.toString();
    }

    private String buildJoinCondition(String table1, String table2, List<String> joinColumns) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0)
                condition.append(" AND ");
            condition.append(capabilityProvider.quote(table1)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" = ")
                    .append(capabilityProvider.quote(table2)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)));
        }
        return condition.toString();
    }
}
