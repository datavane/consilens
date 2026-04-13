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

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public ClickHouseSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
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
            // Two-step approach matching MySQL: per-row MD5 + aggregate MD5
            sql.append("COALESCE(lower(hex(MD5(groupConcat('|')(row_checksum ORDER BY pk_key)))), '') as checksum ");
            sql.append("FROM (");
            sql.append("SELECT ");

            // Build primary key for stable ordering
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

            // Build per-row checksum using MD5 (lower(hex()) to match MySQL's lowercase hex output)
            sql.append("lower(hex(MD5(CONCAT_WS('|', ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append(")))) as row_checksum ");

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
        sql.append(", lower(hex(MD5(CONCAT_WS('|'");

        for (String col : columns) {
            sql.append(", ");
            DataType dataType = columnDataTypes.get(col);
            sql.append(dataTypeHandler.normalizeColumn(col, dataType));
        }

        sql.append(")))) AS row_hash");

        sql.append(" FROM ");
        if (schemaName != null && !schemaName.isEmpty()) {
            sql.append(capabilityProvider.quote(schemaName)).append(".");
        }
        sql.append(capabilityProvider.quote(tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2,
            List<String> joinColumns,
            String whereClause) {
        // ClickHouse DOES support native FULL OUTER JOIN!
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
}
