package com.consilens.connector.api;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.model.DataType;

import java.util.List;
import java.util.Map;

/**
 * Interface for SQL query generation operations.
 * 
 * <p>
 * This interface provides methods for generating various SQL queries including
 * SELECT, INSERT, JOIN, and utility operations. It is a standalone component
 */
public interface SqlQueryGenerator {

        /**
         * Generate a LIMIT clause for pagination.
         *
         * @param offset the number of rows to skip (0 for no offset)
         * @param limit  the maximum number of rows to return
         * @return SQL LIMIT clause
         */
        String getLimitClause(long offset, long limit);

        /**
         * Generate a LIMIT clause without offset.
         *
         * @param limit the maximum number of rows to return
         * @return SQL LIMIT clause
         */
        default String getLimitClause(long limit) {
                return getLimitClause(0, limit);
        }

        /**
         * Generate SQL for counting rows in a table.
         *
         * @param schemaName  the schema name (can be null)
         * @param tableName   the table name
         * @param whereClause optional WHERE clause (can be null)
         * @return SQL COUNT query
         */
        String getCountSQL(String schemaName, String tableName, String whereClause);

        /**
         * Generate SQL for selecting rows from a table.
         *
         * @param schemaName        the schema name (can be null)
         * @param tableName         the table name
         * @param selectExpressions the select expressions or column names
         * @param whereClause       optional WHERE clause (can be null)
         * @param orderByColumns    optional ORDER BY columns (can be null/empty)
         * @return SQL SELECT query
         */
        String getSelectSQL(String schemaName, String tableName,
                        List<String> selectExpressions,
                        String whereClause,
                        List<String> orderByColumns);

        /**
         * Generate SQL for selecting rows by primary keys.
         *
         * @param schemaName        the schema name (can be null)
         * @param tableName         the table name
         * @param selectExpressions the select expressions or column names
         * @param keyColumns        the primary key columns
         * @param primaryKeys       list of primary key values (each entry is a composite key)
         * @param whereClause       optional WHERE clause (can be null)
         * @param orderByColumns    optional ORDER BY columns (can be null/empty)
         * @return SQL SELECT query filtered by keys
         */
        String getSelectByKeysSQL(String schemaName, String tableName,
                        List<String> selectExpressions,
                        List<String> keyColumns,
                        List<List<Object>> primaryKeys,
                        String whereClause,
                        List<String> orderByColumns);

        /**
         * Generate SQL for retrieving MIN/MAX values of key columns.
         *
         * @param schemaName  the schema name (can be null)
         * @param tableName   the table name
         * @param keyColumns  the key columns
         * @param getMin      true for MIN, false for MAX
         * @param whereClause optional WHERE clause (can be null)
         * @return SQL query for min/max key values
         */
        String getMinMaxKeySQL(String schemaName, String tableName,
                        List<String> keyColumns,
                        boolean getMin,
                        String whereClause);

        /**
         * Generate SQL for calculating checksum/hash of table data.
         *
         * @param schemaName      the schema name
         * @param tableName       the table name
         * @param keyColumns      the primary key columns (for stable ordering)
         * @param columns         the columns to include in checksum
         * @param columnDataTypes mapping of column names to their data types
         * @param whereClause     optional WHERE clause
         * @param checksumAlgorithm    the checksum calculation algorithm (CONCAT or XOR)
         * @return SQL checksum query
         */
        String getChecksumSQL(String schemaName, String tableName,
                        List<String> keyColumns,
                        List<String> columns,
                        Map<String, DataType> columnDataTypes,
                        String whereClause,
                        ChecksumAlgorithm checksumAlgorithm);

        /**
         * Generate SQL for calculating checksum/hash of table data (backward compatibility).
         * Uses CONCAT algorithm by default.
         *
         * @param schemaName      the schema name
         * @param tableName       the table name
         * @param keyColumns      the primary key columns (for stable ordering)
         * @param columns         the columns to include in checksum
         * @param columnDataTypes mapping of column names to their data types
         * @param whereClause     optional WHERE clause
         * @return SQL checksum query
         */
        default String getChecksumSQL(String schemaName, String tableName,
                        List<String> keyColumns,
                        List<String> columns,
                        Map<String, DataType> columnDataTypes,
                        String whereClause) {
            return getChecksumSQL(schemaName, tableName, keyColumns, columns, 
                                columnDataTypes, whereClause, 
                                ChecksumAlgorithm.CONCAT);
        }

        /**
         * Generate SQL for counting distinct rows.
         *
         * @param schemaName  the schema name
         * @param tableName   the table name
         * @param columns     the columns for DISTINCT clause
         * @param whereClause optional WHERE clause
         * @return SQL DISTINCT COUNT query
         */
        String getDistinctCountSQL(String schemaName, String tableName,
                        List<String> columns, String whereClause);

        /**
         * Generate SQL for a full outer join between two tables.
         *
         * @param table1      the first table
         * @param table2      the second table
         * @param joinColumns the columns to join on
         * @param whereClause optional WHERE clause
         * @return SQL FULL OUTER JOIN query
         */
        String getFullOuterJoinSQL(String table1, String table2,
                        List<String> joinColumns, String whereClause);

        /**
         * Generate SQL for a left outer join.
         *
         * @param table1      the first table
         * @param table2      the second table
         * @param joinColumns the columns to join on
         * @param whereClause optional WHERE clause
         * @return SQL LEFT OUTER JOIN query
         */
        String getLeftOuterJoinSQL(String table1, String table2,
                        List<String> joinColumns, String whereClause);

        /**
         * Generate SQL for a right outer join.
         *
         * @param table1      the first table
         * @param table2      the second table
         * @param joinColumns the columns to join on
         * @param whereClause optional WHERE clause
         * @return SQL RIGHT OUTER JOIN query
         */
        String getRightOuterJoinSQL(String table1, String table2,
                        List<String> joinColumns, String whereClause);

        /**
         * Generate SQL for inserting data into a table.
         *
         * @param tableName the table name
         * @param columns   the columns to insert
         * @return SQL INSERT statement (without VALUES clause)
         */
        String getInsertSQL(String tableName, List<String> columns);

        /**
         * Generate SQL for batch insert with multiple value sets.
         *
         * @param tableName the table name
         * @param columns   the columns to insert
         * @param batchSize the number of rows in the batch
         * @return SQL INSERT statement with multiple VALUES clauses
         * @throws IllegalArgumentException if batchSize is less than 1
         */
        String getBatchInsertSQL(String tableName, List<String> columns, int batchSize);

        /**
         * Generate SQL for dropping a table.
         *
         * @param tableName the table name
         * @param ifExists  whether to include IF EXISTS clause
         * @return SQL DROP TABLE statement
         */
        String getDropTableSQL(String tableName, boolean ifExists);

        /**
         * Generate SQL for creating a temporary table.
         *
         * @param tempTableName the temporary table name
         * @param selectSQL     the SELECT statement to populate the table
         * @return SQL CREATE TEMPORARY TABLE statement
         */
        String getCreateTempTableSQL(String tempTableName, String selectSQL);

        /**
         * Generate SQL for calculating row hashes used during local comparison.
         * 
         * <p>
         * This method generates a SQL query that computes a hash (MD5) for each row in the table,
         * returning the primary key columns and the row hash. The row hash is computed by:
         * <ol>
         *   <li>Normalizing each column value according to its data type (using canonical representation)</li>
         *   <li>Concatenating all normalized column values with ASCII 31 (0x1F) as separator</li>
         *   <li>Computing MD5 hash of the concatenated string</li>
         * </ol>
         * 
         * <p>
         * The generated SQL should:
         * <ul>
         *   <li>SELECT primary key columns and MD5(canonical_row) AS row_hash</li>
         *   <li>Use CHAR(31) or CHR(31) as column separator (ASCII 0x1F)</li>
         *   <li>Use CHAR(1) or CHR(1) as NULL sentinel (ASCII 0x01)</li>
         *   <li>Note: CHAR(0)/CHR(0) is avoided due to string truncation issues in some databases</li>
         *   <li>Apply data type normalization via DataTypeHandler.normalizeColumn()</li>
         *   <li>NOT include ORDER BY clause (sequential scan for performance)</li>
         *   <li>NOT include GROUP BY or aggregation (row-level hashing only)</li>
         * </ul>
         * 
         * <p>
         * Example output for MySQL:
         * <pre>
         * SELECT pk1, pk2,
         *        MD5(CONCAT_WS(CHAR(31),
         *            COALESCE(NULLIF(CAST(col1 AS CHAR), ''), CHAR(1)),
         *            COALESCE(NULLIF(CAST(col2 AS CHAR), ''), CHAR(1)),
         *            ...
         *        )) AS row_hash
         * FROM schema.table
         * WHERE pk BETWEEN ? AND ?
         * </pre>
         * 
         * <p>
         * Example output for PostgreSQL:
         * <pre>
         * SELECT pk1, pk2,
         *        MD5(CONCAT_WS(CHR(31),
         *            COALESCE(NULLIF(CAST(col1 AS TEXT), ''), CHR(1)),
         *            COALESCE(NULLIF(CAST(col2 AS TEXT), ''), CHR(1)),
         *            ...
         *        )) AS row_hash
         * FROM schema.table
         * WHERE pk BETWEEN ? AND ?
         * </pre>
         * 
         * <p>
         * This method is used by the row-hash local comparison optimization to find differing
         * primary keys before fetching full rows.
         *
         * @param schemaName      the schema name (can be null for databases without schema concept)
         * @param tableName       the table name
         * @param primaryKeys     the primary key column names (used in SELECT clause)
         * @param columns         all columns to include in the row hash computation
         * @param columnDataTypes mapping of column names to their data types (for normalization)
         * @param whereClause     optional WHERE clause for filtering rows (can be null)
         * @return SQL query that returns primary keys and row_hash for each row
         * @see com.consilens.connector.api.DataTypeHandler#normalizeColumn(String, DataType)
         */
        String getRowHashSQL(String schemaName, String tableName,
                        List<String> primaryKeys,
                        List<String> columns,
                        Map<String, DataType> columnDataTypes,
                        String whereClause);

        /**
         * Generate SQL for join diff statistics (source/target/mismatch counts).
         */
        String getJoinDiffStatsSQL(
                        String schema1, String table1, String alias1,
                        List<String> keyColumns1, List<String> compareColumns1, String where1,
                        String schema2, String table2, String alias2,
                        List<String> keyColumns2, List<String> compareColumns2, String where2);

        /**
         * Generate SQL for join diff detail rows with diff type and diff column lists.
         */
        String getJoinDiffDetailSQL(
                        String schema1, String table1, String alias1,
                        List<String> keyColumns1, List<String> compareColumns1, List<String> outputColumns1, String where1,
                        String schema2, String table2, String alias2,
                        List<String> keyColumns2, List<String> compareColumns2, List<String> outputColumns2, String where2);

        /**
         * Format a value for SQL literal usage.
         *
         * @param value the value to format
         * @return formatted SQL literal
         */
        default String formatValue(Object value) {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof String) {
                String str = value.toString().replace("'", "''");
                return "'" + str + "'";
            }
            if (value instanceof Number) {
                return value.toString();
            }
            if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp) {
                return "'" + value.toString() + "'";
            }
            String str = value.toString().replace("'", "''");
            return "'" + str + "'";
        }
}
