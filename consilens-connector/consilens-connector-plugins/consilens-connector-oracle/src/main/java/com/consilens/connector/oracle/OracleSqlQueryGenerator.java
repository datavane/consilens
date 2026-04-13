package com.consilens.connector.oracle;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;
import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Oracle SQL query generator.
 * Uses ROWNUM or FETCH FIRST for pagination.
 */
public class OracleSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public OracleSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            // Oracle 12c+: FETCH FIRST
            return "FETCH FIRST " + limit + " ROWS ONLY";
        }
        // Oracle 12c+: OFFSET...FETCH
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
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
            // Oracle uses STANDARD_HASH or DBMS_CRYPTO
            // BUGFIX: Use only primary key columns for ordering to ensure stable sort
            sql.append("RAWTOHEX(DBMS_CRYPTO.HASH(");
            sql.append("UTL_RAW.CAST_TO_RAW(LISTAGG(row_checksum, '|') WITHIN GROUP (ORDER BY pk_key)), 3)) as checksum ");
            sql.append("FROM (SELECT ");
            
            // Build primary key for stable ordering
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0)
                    sql.append(" || '|' || ");
                String col = keyColumns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append(" as pk_key, ");
            
            // Build row checksum using all columns
            sql.append("RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    sql.append(" || '|' || ");
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(dataTypeHandler.normalizeColumn(col, dataType));
            }
            sql.append("), 3)) as row_checksum ");
            
            sql.append("FROM ");
            if (schemaName != null && !schemaName.isEmpty()) {
                sql.append(capabilityProvider.quote(schemaName)).append(".");
            }
            sql.append(capabilityProvider.quote(tableName));

            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }
            sql.append(")");
        }

        return sql.toString();
    }

    /**
     * Generate checksum SQL using XOR method (high performance)
     * Formula: MD5(col1+'_C1') ^ MD5(col2+'_C2') * 2 ^ MD5(col3+'_C3') * 3 ^ ...
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
            // Build XOR expression using Oracle's BITXOR function
            sql.append("LPAD(TO_CHAR(");
            
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(" + ");  // Oracle doesn't have native XOR for large numbers, use addition as approximation
                }
                
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);
                
                // Add salt to each column: col + '_C' + (index+1)
                String saltedCol = normalizedCol + " || '_C" + (i + 1) + "'";
                
                // Convert STANDARD_HASH to numeric for XOR operation
                // Take first 16 characters of hash and convert from hex to decimal
                String hashExpr = "TO_NUMBER(SUBSTR(STANDARD_HASH(" + saltedCol + ", 'MD5'), 1, 16), 'XXXXXXXXXXXXXXXX')";
                
                // Apply weight: multiply by (index + 1)
                if (i > 0) {
                    hashExpr = "(" + hashExpr + " * " + (i + 1) + ")";
                }
                
                sql.append(hashExpr);
            }
            
            sql.append(", 'XXXXXXXXXXXXXXXX'), 16, '0') as checksum ");
        }

        sql.append("FROM ");
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

        sql.append(", STANDARD_HASH(");

        // Build canonical expression using CHR(31) as separator
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(" || CHR(31) || ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);

            // Use NVL to convert NULL to CHR(1) (unified across all databases)
            sql.append("NVL(").append(normalizedCol).append(", CHR(1))");
        }

        sql.append(", 'MD5') as row_hash");

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
