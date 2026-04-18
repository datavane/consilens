package com.consilens.core.database.adpter;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.segment.TableSegment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unified database adapter interface for the integrated system.
 * Combines the best features from all modules.
 */
public interface DatabaseAdapter {

    /**
     * Get the database name.
     */
    String getName();

    /**
     * Get the connector type identifier (e.g. "mysql", "postgresql").
     */
    String getConnectorType();

    /**
     * Get the connection pool for this adapter.
     */
    ConnectionPool getConnectionPool();

    /**
     * Get table schema.
     */
    TableSchema getTableSchema(List<String> tablePath);

    /**
     * Count rows in a table segment.
     */
    long count(TableSegment segment);

    /**
     * Count rows and calculate checksum for a table segment.
     */
    TableSegment.ChecksumResult countAndChecksum(TableSegment segment);

    /**
     * Count rows and get bounds (minKey, maxKey) without computing checksum.
     * This is much faster than countAndChecksum() for initial bounds calculation.
     * 
     * @param segment the table segment
     * @return ChecksumResult with count, minKey, maxKey (checksum will be null)
     * @since 1.1.0
     */
    TableSegment.ChecksumResult countAndBounds(TableSegment segment);

    /**
     * Query rows from a table segment.
     */
    List<Object[]> querySegment(TableSegment segment);

    /**
     * Query row hashes from a table segment (primary key + row hash only).
     * This is much more efficient than querySegment() for large segments with many columns.
     * 
     * <p>Returns a map where:
     * <ul>
     *   <li>Key: List of primary key values (in order of keyColumns)</li>
     *   <li>Value: MD5 hash of the entire row (hex string)</li>
     * </ul>
     * 
     * <p>This method is used for efficient difference detection:
     * <ol>
     *   <li>Query row hashes from both tables (lightweight)</li>
     *   <li>Compare hashes in memory to find mismatched keys</li>
     *   <li>Query full row data only for mismatched keys</li>
     * </ol>
     * 
     * @param segment the table segment to query
     * @return Map of primary key values to row hash
     * @since 1.1.0
     */
    java.util.Map<List<Object>, String> querySegmentRowHashes(TableSegment segment);

    /**
     * Query rows from a table segment for specific primary keys only.
     * This is used to fetch detailed data for rows that have differences.
     * 
     * @param segment the table segment
     * @param primaryKeys set of primary key values to fetch
     * @return List of row data arrays (only for the specified keys)
     * @since 1.1.0
     */
    List<Object[]> querySegmentByKeys(TableSegment segment, java.util.Set<List<Object>> primaryKeys);

    /**
     * Build a sampling query to fetch key values at a specific row offset.
     *
     * @param segment the table segment
     * @param offset the row offset
     * @param limit the maximum rows to return
     * @return SQL query string for sampling key values
     */
    String buildKeySamplingQuery(TableSegment segment, long offset, long limit);

    /**
     * Convenience overload: sample a single row at the given offset.
     */
    default String buildKeySamplingQuery(TableSegment segment, long offset) {
        return buildKeySamplingQuery(segment, offset, 1);
    }

    /**
     * Build a keyset pagination sampling query to fetch key values after the given key.
     *
     * @param segment the table segment
     * @param lastKey the last key from previous page
     * @param limit the maximum rows to return
     * @return SQL query string for keyset sampling
     */
    String buildKeysetSamplingQuery(TableSegment segment, List<Object> lastKey, long limit);

    /**
     * Whether the database adapter supports keyset sampling.
     */
    default boolean supportsKeysetSampling() {
        return true;
    }

    /**
     * Build a count query for the given table segment.
     *
     * @param segment the table segment
     * @return SQL count query string
     */
    String buildCountQuery(TableSegment segment);

    /**
     * Execute a query and return results.
     */
    <T> List<T> query(String sql, Class<T> resultType);

    /**
     * Execute a query with parameters.
     */
    <T> List<T> query(String sql, Class<T> resultType, Object... parameters);

    /**
     * Execute an update statement.
     */
    int executeUpdate(String sql);

    /**
     * Execute an update statement with parameters.
     */
    int executeUpdate(String sql, Object... params);

    /**
     * Execute a batch of update statements.
     */
    int[] executeBatch(String sql, List<Object[]> paramsList);

    /**
     * Execute a query and process results with a row mapper.
     */
    <T> List<T> query(String sql, RowMapper<T> rowMapper);

    /**
     * Execute a query with parameters and process results with a row mapper.
     */
    <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... params);

    /**
     * Execute a query and return a single result (or null).
     */
    <T> Optional<T> queryForObject(String sql, Class<T> resultType, Object... params);

    /**
     * Execute a query and return a single result using row mapper (or null).
     */
    <T> Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params);

    /**
     * Execute a query and return the count of affected rows.
     */
    long countRows(String sql, Object... params);

    /**
     * Check if the database supports a specific feature.
     */
    default boolean supportsFeature(DatabaseFeature feature) {
        return getDialect().getCapabilityProvider().supportsFeature(feature);
    }

    /**
     * Get a raw JDBC connection.
     */
    Connection getConnection() throws SQLException;

    /**
     * Get SQL query generator for this adapter.
     */
    SqlQueryGenerator getSqlQueryGenerator();

    /**
     * Execute within a transaction.
     */
    <T> T executeInTransaction(TransactionCallback<T> callback) throws SQLException;

    /**
     * Check database health and connectivity.
     */
    boolean isHealthy();

    /**
     * Get database metadata and statistics.
     */
    Map<String, Object> getMetadata();

    /**
     * Check if database supports native parallelization.
     */
    default boolean supportsNativeParallelization() {
        return supportsFeature(DatabaseFeature.NATIVE_PARALLELIZATION);
    }

    /**
     * Check if database supports unique constraints.
     */
    default boolean supportsUniqueConstraints() {
        return supportsFeature(DatabaseFeature.UNIQUE_CONSTRAINTS);
    }

    /**
     * Drop table if exists.
     */
    void dropTableIfExists(String tableName);

    /**
     * Insert data into table.
     */
    void insertIntoTable(String tableName, String sql, int limit);

    /**
     * Quote identifier for SQL.
     */
    String quote(String identifier);

    /**
     * Get database-specific SQL dialect helper.
     */
    DatabaseDialect getDialect();

    /**
     * Close database adapter and release resources.
     */
    void close();

    /**
     * Row mapper interface for processing ResultSet rows.
     */
    @FunctionalInterface
    interface RowMapper<T> {
        T mapRow(ResultSet rs, int rowNum) throws SQLException;
    }

    /**
     * Transaction callback interface.
     */
    @FunctionalInterface
    interface TransactionCallback<T> {
        T doInTransaction(Connection connection) throws SQLException;
    }

    /**
     * Utility interface for building prepared statements.
     */
    interface PreparedStatementCreator {
        PreparedStatement createPreparedStatement(Connection connection) throws SQLException;
    }

    /**
     * Utility interface for parameter setting.
     */
    @FunctionalInterface
    interface PreparedStatementSetter {
        void setValues(PreparedStatement ps) throws SQLException;
    }
}
