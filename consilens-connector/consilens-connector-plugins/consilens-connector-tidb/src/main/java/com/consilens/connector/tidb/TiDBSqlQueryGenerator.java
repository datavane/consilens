package com.consilens.connector.tidb;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;
import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TiDB SQL query generator.
 * 
 * <p>
 * Implements TiDB-specific SQL syntax.
 * TiDB is highly compatible with MySQL, so this generator shares much logic
 * with MySQLSqlQueryGenerator.
 * </p>
 * 
 * @since 1.0.0
 */
@Slf4j
public class TiDBSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public TiDBSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }

    @Override
    protected String buildNullSafeNotEquals(String left, String right) {
        return "NOT (" + left + " <=> " + right + ")";
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        // TiDB supports MySQL syntax: LIMIT offset, limit
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
            // BUGFIX: Use only primary key columns for ordering to ensure stable sort
            // TiDB is MySQL-compatible, use same approach as MySQL
            sql.append("COALESCE(MD5(GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')), '') as checksum ");
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

            // Build row checksum using all columns
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
            sql.append(buildRelationRef(schemaName, tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            sql.append(") AS data");
        }

        log.info("checksum sql: {}", sql.toString());

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2,
            List<String> joinColumns,
            String whereClause) {
        // TiDB doesn't support FULL OUTER JOIN directly, simulate using UNION
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
}
