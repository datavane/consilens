package com.consilens.core.database.adpter;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.connector.api.model.DataType;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.util.ResourceManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * Abstract base class for database adapters providing common functionality.
 * Integrates best practices from all modules.
 */
@Slf4j
public abstract class AbstractDatabaseAdapter implements DatabaseAdapter {

    protected final ConnectionPool connectionPool;
    protected final DatabaseDialect dialect;
    protected final String name;
    protected final String connectorType;
    protected final ChecksumAlgorithm checksumAlgorithm;

    protected AbstractDatabaseAdapter(String name, ConnectionPool connectionPool, DatabaseDialect dialect, ChecksumAlgorithm checksumAlgorithm) {
        this.name = name;
        this.connectionPool = connectionPool;
        this.dialect = dialect;
        this.connectorType = connectionPool.getConnectorType();
        this.checksumAlgorithm = checksumAlgorithm != null ? checksumAlgorithm : ChecksumAlgorithm.CONCAT;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getConnectorType() {
        return connectorType;
    }

    @Override
    public ConnectionPool getConnectionPool() {
        return connectionPool;
    }

    @Override
    public DatabaseDialect getDialect() {
        return dialect;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionPool.getConnection();
    }

    @Override
    public <T> List<T> query(String sql, Class<T> resultType) {
        return query(sql, (rs, rowNum) -> {
            try {
                if (resultType == String.class) {
                    return resultType.cast(rs.getString(1));
                } else if (resultType == Integer.class || resultType == int.class) {
                    return resultType.cast(rs.getInt(1));
                } else if (resultType == Long.class || resultType == long.class) {
                    return resultType.cast(rs.getLong(1));
                } else if (resultType == Double.class || resultType == double.class) {
                    return resultType.cast(rs.getDouble(1));
                } else if (resultType == Boolean.class || resultType == boolean.class) {
                    return resultType.cast(rs.getBoolean(1));
                } else if (resultType == java.sql.Date.class) {
                    return resultType.cast(rs.getDate(1));
                } else if (resultType == java.util.Date.class) {
                    return resultType.cast(rs.getTimestamp(1));
                } else if (resultType.isArray() && resultType.getComponentType() == Object.class) {
                    // Handle Object[] case - read all columns into array
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    Object[] row = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        // CRITICAL: Use getString() instead of getObject() to ensure
                        // normalized values (especially BLOB/HEX conversions) are read as strings
                        // This prevents byte[] from being returned for BLOB columns that were
                        // converted to HEX strings in the SQL query
                        String value = rs.getString(i + 1);
                        row[i] = value;
                        
                        // Debug log for DECIMAL and BLOB columns
                        String columnName = metaData.getColumnLabel(i + 1);
                        if (columnName != null && (columnName.toLowerCase().contains("decimal") || 
                                                   columnName.toLowerCase().contains("numeric") ||
                                                   columnName.toLowerCase().contains("blob"))) {
                            log.debug("Read column {} (type={}): value=[{}]", 
                                    columnName, metaData.getColumnTypeName(i + 1), value);
                        }
                    }
                    return resultType.cast(row);
                } else {
                    return resultType.cast(rs.getObject(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error mapping result to type " + resultType, e);
            }
        });
    }

    @Override
    public <T> List<T> query(String sql, Class<T> resultType, Object... parameters) {
        return query(sql, createResultSetMapper(resultType), parameters);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        return query(sql, rowMapper, new Object[0]);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... parameters) {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            setParameters(statement, parameters);
            resultSet = statement.executeQuery();

            return processResultSet(resultSet, rowMapper);
        } catch (SQLException e) {
            throw new RuntimeException("Error executing query: " + sql, e);
        } finally {
            ResourceManager.closeJdbcResources(statement, resultSet);
            releaseQuietly(connection);
        }
    }

    @Override
    public <T> Optional<T> queryForObject(String sql, Class<T> resultType, Object... params) {
        List<T> results = query(sql, resultType, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public <T> Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
        List<T> results = query(sql, rowMapper, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public long countRows(String sql, Object... params) {
        Optional<Long> result = queryForObject(sql, Long.class, params);
        return result.orElse(0L);
    }

    @Override
    public int executeUpdate(String sql) {
        return executeUpdate(sql, new Object[0]);
    }

    @Override
    public int executeUpdate(String sql, Object... parameters) {
        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            setParameters(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error executing update: " + sql, e);
        } finally {
            ResourceManager.closeQuietly(statement);
            releaseQuietly(connection);
        }
    }

    @Override
    public int[] executeBatch(String sql, List<Object[]> paramsList) {
        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);

            for (Object[] params : paramsList) {
                setParameters(statement, params);
                statement.addBatch();
            }

            return statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Error executing batch: " + sql, e);
        } finally {
            ResourceManager.closeQuietly(statement);
            releaseQuietly(connection);
        }
    }

    @Override
    public <T> T executeInTransaction(TransactionCallback<T> callback) throws SQLException {
        Connection connection = null;
        try {
            connection = getConnection();
            connection.setAutoCommit(false);

            T result = callback.doInTransaction(connection);

            connection.commit();
            return result;
        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    log.error("Error during transaction rollback", rollbackEx);
                }
            }
            throw e;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    log.warn("Error resetting auto-commit", e);
                }
                releaseQuietly(connection);
            }
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            String healthSql = dialect.getMetadataQueryGenerator().getHealthCheckSQL();
            if (healthSql != null && !healthSql.trim().isEmpty()) {
                Optional<Object> result = queryForObject(healthSql, Object.class);
                return result.isPresent();
            } else {
                // Fallback: try to get a connection and validate it
                try (Connection conn = getConnection()) {
                    return conn != null && !conn.isClosed() && conn.isValid(5);
                }
            }
        } catch (Exception e) {
            log.warn("Database health check failed", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getMetadata() {
        Map<String, Object> metadata = new HashMap<>();

        try {
            metadata.put("databaseType", connectorType);
            metadata.put("databaseName", name);
            metadata.put("healthy", isHealthy());
            metadata.put("supportsFeatures", dialect.getCapabilityProvider().getSupportedFeatures());

            // Add connection pool statistics
            ConnectionPool.PoolStatistics stats = connectionPool.getStatistics();
            metadata.put("activeConnections", stats.getActiveConnections());
            metadata.put("idleConnections", stats.getIdleConnections());
            metadata.put("totalConnections", stats.getTotalConnections());
            metadata.put("utilizationPercentage", stats.getUtilizationPercentage());

            // Add connection pool configuration
            PoolConfiguration config = connectionPool.getConfiguration();
            metadata.put("maxPoolSize", config.getMaxPoolSize());
            metadata.put("minIdleConnections", config.getMinIdle());

            // Add dialect information
            metadata.put("dialect", dialect.getClass().getSimpleName());
            metadata.put("openQuote", dialect.getCapabilityProvider().getOpenQuote());
            metadata.put("closeQuote", dialect.getCapabilityProvider().getCloseQuote());

        } catch (Exception e) {
            log.error("Error collecting database metadata", e);
            metadata.put("error", e.getMessage());
        }

        return metadata;
    }

    @Override
    public String quote(String identifier) {
        return dialect.getCapabilityProvider().quote(identifier);
    }

    @Override
    public void dropTableIfExists(String tableName) {
        String sql = dialect.getSqlQueryGenerator().getDropTableSQL(tableName, true);
        executeUpdate(sql);
    }

    @Override
    public void insertIntoTable(String tableName, String sql, int limit) {
        executeUpdate(sql);
    }

    @Override
    public void close() {
        connectionPool.close();
        log.info("Database adapter '{}' closed", name);
    }

    @Override
    public TableSegment.ChecksumResult countAndChecksum(TableSegment segment) {
        try {
            // Build simple checksum query
            List<String> relevantColumns = segment.getRelevantColumns();
            String checksumQuery = buildChecksumQuery(segment, relevantColumns);

            log.info("Executing checksum query: {}", checksumQuery);

            // Execute query using a RowMapper that returns Maps
            List<Map<String, Object>> results = query(checksumQuery, (rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                int columnCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = rs.getMetaData().getColumnLabel(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, value);
                }
                return row;
            });

            if (results.isEmpty()) {
                return new TableSegment.ChecksumResult(0, null, null, null);
            }

            Map<String, Object> result = results.get(0);
            long rowCount = ((Number) result.get("row_count")).longValue();
            String checksum = (String) result.get("checksum");

            // Get min/max key values separately if needed
            List<Object> minKey = null;
            List<Object> maxKey = null;
            if (!segment.getKeyColumns().isEmpty()) {
                minKey = getMinMaxKey(segment, true);
                maxKey = getMinMaxKey(segment, false);
            }

            return new TableSegment.ChecksumResult(rowCount, checksum, minKey, maxKey);

        } catch (Exception e) {
            log.error("Error calculating checksum for segment: {}", segment.getTablePath(), e);
            throw new RuntimeException("Checksum calculation failed", e);
        }
    }

    @Override
    public TableSegment.ChecksumResult countAndBounds(TableSegment segment) {
        try {
            // Build simple count query without expensive checksum calculation
            String countQuery = buildCountQuery(segment);

            log.debug("Executing count query (without checksum): {}", countQuery);

            // Execute count query
            List<Map<String, Object>> results = query(countQuery.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("row_count", rs.getObject(1));
                return row;
            });

            if (results.isEmpty()) {
                return new TableSegment.ChecksumResult(0, null, null, null);
            }

            long rowCount = ((Number) results.get(0).get("row_count")).longValue();

            // Get min/max key values if needed
            List<Object> minKey = null;
            List<Object> maxKey = null;
            if (!segment.getKeyColumns().isEmpty()) {
                minKey = getMinMaxKey(segment, true);
                maxKey = getMinMaxKey(segment, false);
            }

            // Return result without checksum (null)
            return new TableSegment.ChecksumResult(rowCount, null, minKey, maxKey);

        } catch (Exception e) {
            log.error("Error getting count and bounds for segment: {}", segment.getTablePath(), e);
            throw new RuntimeException("Count and bounds calculation failed", e);
        }
    }

    /**
     * Build checksum query for the segment using database dialect.
     */
    protected String buildChecksumQuery(TableSegment segment, List<String> columns) {
        // Use the database dialect to generate the appropriate checksum SQL
        String whereClause = segment.buildWhereClause();

        // Extract column data types from the schema
        Map<String, DataType> columnDataTypes = new HashMap<>();
        TableSchema tableSchema;
        // If segment doesn't have schema, fetch it from database
        if (segment.getSchema().isPresent()) {
            tableSchema = segment.getSchema().get();
        } else {
            if (segment.hasRelationSource()) {
                throw new IllegalStateException("Schema is required for SQL relation segment: " + segment.getDisplayName());
            }
            // Fetch schema from database
            List<String> tablePathComponents = segment.getTablePath().getComponents();
            tableSchema = getTableSchema(tablePathComponents);
            segment.withSchema(tableSchema);
        }
        // Now extract data types for all columns
        for (String column : columns) {
            DataType dataType = tableSchema.getColumnType(column);
            columnDataTypes.put(column, dataType);
        }

        // BUGFIX: Pass key columns separately for stable ordering
        List<String> keyColumns = segment.getKeyColumns();

        log.info("Building checksum query for table: {}, keyColumns: {}, columns: {}, where: {}, checksumAlgorithm: {}",
                segment.getDisplayName(), keyColumns, columns, whereClause, this.checksumAlgorithm);

        if (segment.hasRelationSource()) {
            return dialect.getSqlQueryGenerator().getChecksumSQLFromSql(
                    segment.getRelationFromSql(),
                    keyColumns,
                    columns,
                    columnDataTypes,
                    whereClause,
                    this.checksumAlgorithm);
        }

        String schemaName = segment.getTablePath().getSchema().orElse(null);
        String tableName = segment.getTablePath().getTableName();
        return dialect.getSqlQueryGenerator().getChecksumSQL(schemaName, tableName, keyColumns, columns,
                columnDataTypes, whereClause, this.checksumAlgorithm);
    }


    /**
     * Build database-specific checksum expression.
     */
    protected String buildDatabaseChecksumExpression(List<String> columns, TableSegment segment) {
        if (columns.isEmpty()) {
            return "''";
        }

        String databaseType = getConnectorType();

        switch (databaseType) {
            case "postgresql":
                return buildPostgreSQLChecksum(columns);
            case "mysql":
                return buildMySQLChecksum(columns);
            case "oracle":
                return buildOracleChecksum(columns);
            case "sqlserver":
            case "mssql":
                return buildSQLServerChecksum(columns);
            default:
                return buildGenericChecksum(columns);
        }
    }

    /**
     * PostgreSQL checksum expression.
     */
    private String buildPostgreSQLChecksum(List<String> columns) {
        StringBuilder expr = new StringBuilder();
        if (columns.size() == 1) {
            expr.append("COALESCE(CAST(").append(columns.get(0)).append(" AS TEXT), '')");
        } else {
            expr.append("MD5(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    expr.append(" || '|' || ");
                }
                expr.append("COALESCE(CAST(").append(columns.get(i)).append(" AS TEXT), '')");
            }
            expr.append(")");
        }
        return expr.toString();
    }

    /**
     * MySQL checksum expression.
     */
    private String buildMySQLChecksum(List<String> columns) {
        StringBuilder expr = new StringBuilder();
        expr.append("MD5(CONCAT_WS('|'");
        for (String column : columns) {
            expr.append(", COALESCE(CAST(").append(column).append(" AS CHAR), '')");
        }
        expr.append("))");
        return expr.toString();
    }

    /**
     * Oracle checksum expression.
     */
    private String buildOracleChecksum(List<String> columns) {
        StringBuilder expr = new StringBuilder();
        expr.append("STANDARD_HASH(");
        if (columns.size() == 1) {
            expr.append("COALESCE(TO_CHAR(").append(columns.get(0)).append("), '')");
        } else {
            expr.append("CONCAT(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    expr.append(", '|', ");
                expr.append("COALESCE(TO_CHAR(").append(columns.get(i)).append("), '')");
            }
            expr.append(")");
        }
        expr.append(", 'MD5')");
        return expr.toString();
    }

    /**
     * SQL Server checksum expression.
     */
    private String buildSQLServerChecksum(List<String> columns) {
        StringBuilder expr = new StringBuilder();
        expr.append("CONVERT(VARCHAR(32), HASHBYTES('MD5', ");
        if (columns.size() == 1) {
            expr.append("COALESCE(CAST(").append(columns.get(0)).append(" AS VARCHAR(MAX)), '')");
        } else {
            expr.append("CONCAT(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    expr.append(", '|', ");
                expr.append("COALESCE(CAST(").append(columns.get(i)).append(" AS VARCHAR(MAX)), '')");
            }
            expr.append(")");
        }
        expr.append("))");
        return expr.toString();
    }

    /**
     * Generic checksum expression.
     */
    private String buildGenericChecksum(List<String> columns) {
        StringBuilder expr = new StringBuilder();
        expr.append("MD5(CONCAT(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0)
                expr.append(", '|', ");
            expr.append("COALESCE(CAST(").append(columns.get(i)).append(" AS VARCHAR), '')");
        }
        expr.append("))");
        return expr.toString();
    }

    /**
     * Get min or max key values for the segment.
     */
    private List<Object> getMinMaxKey(TableSegment segment, boolean getMin) {
        if (segment.getKeyColumns().isEmpty()) {
            return null;
        }

        String whereClause = segment.buildWhereClause();
        String sql = segment.hasRelationSource()
                ? dialect.getSqlQueryGenerator().getMinMaxKeySQLFromSql(
                        segment.getRelationFromSql(), segment.getKeyColumns(), getMin, whereClause)
                : dialect.getSqlQueryGenerator().getMinMaxKeySQL(
                        segment.getTablePath().getSchema().orElse(null),
                        segment.getTablePath().getTableName(),
                        segment.getKeyColumns(),
                        getMin,
                        whereClause);

        try {
            List<Object[]> results = query(sql, Object[].class);
            if (!results.isEmpty()) {
                Object[] row = results.get(0);
                List<Object> keyValues = new ArrayList<>();
                for (Object value : row) {
                    keyValues.add(value);
                }
                return keyValues;
            }
        } catch (ClassCastException e) {
            // Handle case where only one column is returned (Single value)
            log.debug("Single column result, handling as single value");
            try {
                List<Object> singleResults = query(sql, Object.class);
                if (!singleResults.isEmpty()) {
                    return Collections.singletonList(singleResults.get(0));
                }
            } catch (Exception e2) {
                log.warn("Error getting min/max key values even with single column approach", e2);
            }
        } catch (Exception e) {
            log.warn("Error getting min/max key values", e);
        }

        return null;
    }

    @Override
    public List<Object[]> querySegment(TableSegment segment) {
        try {
            // Build normalized SELECT query for segment (same as querySegmentByKeys)
            // This ensures all comparison modes use database-side normalization
            String selectQuery = buildNormalizedQuery(segment);

            log.debug("Executing normalized segment query: {}", selectQuery);

            // Execute query and return results
            return query(selectQuery, Object[].class);

        } catch (Exception e) {
            log.error("Error querying segment: {}", segment.getTablePath(), e);
            throw new RuntimeException("Segment query failed", e);
        }
    }

    @Override
    public Map<List<Object>, String> querySegmentRowHashes(TableSegment segment) {
        try {
            String rowHashQuery = buildRowHashQuery(segment);

            log.debug("Executing row hash query for segment: {}, keys: {}", 
                    segment.getTablePath(), segment.getKeyColumns().size());

            Map<List<Object>, String> rowHashes = new LinkedHashMap<>();

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(rowHashQuery)) {
                statement.setFetchSize(1000);
                try (ResultSet resultSet = statement.executeQuery()) {
                    int keyColumnCount = segment.getKeyColumns().size();
                    int rowCount = 0;

                    while (resultSet.next()) {
                        // Extract primary key values
                        List<Object> primaryKey = new ArrayList<>(keyColumnCount);
                        for (int i = 1; i <= keyColumnCount; i++) {
                            primaryKey.add(resultSet.getObject(i));
                        }

                        // Extract row hash (last column)
                        String rowHash = resultSet.getString(resultSet.getMetaData().getColumnCount());
                        rowHashes.put(primaryKey, rowHash);
                        rowCount++;
                    }

                    log.debug("Retrieved {} row hashes for segment: {}", rowHashes.size(), segment.getTablePath());
                    return rowHashes;
                }
            }

        } catch (Exception e) {
            log.error("Error querying row hashes for segment: {}", segment.getTablePath(), e);
            throw new RuntimeException("Row hash query failed", e);
        }
    }

    @Override
    public List<Object[]> querySegmentByKeys(TableSegment segment, Set<List<Object>> primaryKeys) {
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            return Collections.emptyList();
        }

        // For large key sets, split into batches to avoid SQL length limits and poor performance
        final int BATCH_SIZE = 500;  // Query 500 keys at a time
        
        if (primaryKeys.size() <= BATCH_SIZE) {
            return querySegmentByKeysBatch(segment, primaryKeys);
        }

        // Split into batches
        List<Object[]> allResults = new ArrayList<>();
        List<List<Object>> keyList = new ArrayList<>(primaryKeys);
        
        for (int i = 0; i < keyList.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, keyList.size());
            Set<List<Object>> batch = new HashSet<>(keyList.subList(i, end));
            
            log.debug("Querying batch {}/{} ({} keys) for segment: {}", 
                    (i / BATCH_SIZE) + 1, (keyList.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batch.size(), segment.getTablePath());
            
            List<Object[]> batchResults = querySegmentByKeysBatch(segment, batch);
            allResults.addAll(batchResults);
        }

        log.debug("Retrieved total {} rows for {} keys in {} batches", 
                allResults.size(), primaryKeys.size(), (keyList.size() + BATCH_SIZE - 1) / BATCH_SIZE);

        return allResults;
    }

    @Override
    public String buildKeySamplingQuery(TableSegment segment, long offset, long limit) {
        List<String> keyColumns = segment.getKeyColumns();
        String whereClause = segment.buildWhereClause();

        String baseSelect = selectSql(segment, keyColumns, whereClause, keyColumns);

        String limitClause = dialect.getSqlQueryGenerator().getLimitClause(offset, limit);
        return baseSelect + " " + limitClause;
    }

    @Override
    public String buildKeysetSamplingQuery(TableSegment segment, List<Object> lastKey, long limit) {
        if (lastKey == null || lastKey.isEmpty()) {
            throw new IllegalArgumentException("lastKey is required for keyset sampling");
        }

        List<String> keyColumns = segment.getKeyColumns();
        String baseWhere = segment.buildWhereClause();

        if (keyColumns.size() != lastKey.size()) {
            throw new IllegalArgumentException("Key column count does not match lastKey size");
        }

        String keysetClause = buildKeysetWhereClause(keyColumns, lastKey);
        String whereClause;
        if (baseWhere != null && !baseWhere.isBlank()) {
            whereClause = "(" + baseWhere + ") AND (" + keysetClause + ")";
        } else {
            whereClause = keysetClause;
        }

        String baseSelect = selectSql(segment, keyColumns, whereClause, keyColumns);

        String limitClause = dialect.getSqlQueryGenerator().getLimitClause(0, limit);
        return baseSelect + " " + limitClause;
    }

    @Override
    public String buildCountQuery(TableSegment segment) {
        String whereClause = segment.buildWhereClause();
        if (segment.hasRelationSource()) {
            return dialect.getSqlQueryGenerator().getCountSQLFromSql(segment.getRelationFromSql(), whereClause);
        }
        String schemaName = segment.getTablePath().getSchema().orElse(null);
        String tableName = segment.getTablePath().getTableName();
        return dialect.getSqlQueryGenerator().getCountSQL(schemaName, tableName, whereClause);
    }

    private String buildKeysetWhereClause(List<String> keyColumns, List<Object> lastKey) {
        StringBuilder clause = new StringBuilder();
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                clause.append(" OR ");
            }
            StringBuilder andClause = new StringBuilder();
            for (int j = 0; j < i; j++) {
                andClause.append(quoteColumn(keyColumns.get(j))).append(" = ")
                        .append(dialect.getSqlQueryGenerator().formatValue(lastKey.get(j)))
                        .append(" AND ");
            }
            andClause.append(quoteColumn(keyColumns.get(i))).append(" > ")
                    .append(dialect.getSqlQueryGenerator().formatValue(lastKey.get(i)));
            clause.append("(").append(andClause).append(")");
        }
        return clause.toString();
    }

    private String selectSql(TableSegment segment,
                             List<String> selectExpressions,
                             String whereClause,
                             List<String> orderByColumns) {
        if (segment.hasRelationSource()) {
            return dialect.getSqlQueryGenerator().getSelectSQLFromSql(
                    segment.getRelationFromSql(),
                    selectExpressions,
                    whereClause,
                    orderByColumns);
        }
        return dialect.getSqlQueryGenerator().getSelectSQL(
                segment.getTablePath().getSchema().orElse(null),
                segment.getTablePath().getTableName(),
                selectExpressions,
                whereClause,
                orderByColumns);
    }

    private String selectByKeysSql(TableSegment segment,
                                   List<String> selectExpressions,
                                   List<String> keyColumns,
                                   List<List<Object>> primaryKeys,
                                   String whereClause,
                                   List<String> orderByColumns) {
        if (segment.hasRelationSource()) {
            return dialect.getSqlQueryGenerator().getSelectByKeysSQLFromSql(
                    segment.getRelationFromSql(),
                    selectExpressions,
                    keyColumns,
                    primaryKeys,
                    whereClause,
                    orderByColumns);
        }
        return dialect.getSqlQueryGenerator().getSelectByKeysSQL(
                segment.getTablePath().getSchema().orElse(null),
                segment.getTablePath().getTableName(),
                selectExpressions,
                keyColumns,
                primaryKeys,
                whereClause,
                orderByColumns);
    }

    private String quoteColumn(String column) {
        return dialect.getCapabilityProvider().quote(column);
    }

    /**
     * Query segment by keys for a single batch (internal method).
     * 
     * CRITICAL: In DB_ROW_HASH mode, this returns database-normalized values
     * to ensure consistency with hash calculation. The returned data contains
     * normalized string values, not raw database types.
     */
    private List<Object[]> querySegmentByKeysBatch(TableSegment segment, Set<List<Object>> primaryKeys) {
        try {
            // Build query with normalized columns (similar to row-hash query)
            // This ensures the data we compare matches the data used for hash calculation
            String selectQuery = buildNormalizedQueryByKeys(segment, primaryKeys);

            log.debug("Executing normalized selective query for {} keys in segment: {}", 
                    primaryKeys.size(), segment.getTablePath());

            // Execute query and return results
            List<Object[]> results = query(selectQuery, Object[].class);

            log.debug("Retrieved {} normalized rows for {} keys", results.size(), primaryKeys.size());

            return results;

        } catch (Exception e) {
            log.error("Error querying segment by keys: {}", segment.getTablePath(), e);
            throw new RuntimeException("Segment query by keys failed", e);
        }
    }

    /**
     * Build SELECT query for specific primary keys.
     */
    protected String buildSelectQueryByKeys(TableSegment segment, Set<List<Object>> primaryKeys) {
        List<String> relevantColumns = segment.getRelevantColumns();
        List<String> keyColumns = segment.getKeyColumns();
        String whereClause = segment.buildWhereClause();
        List<List<Object>> keys = new ArrayList<>(primaryKeys);

        return selectByKeysSql(segment, relevantColumns, keyColumns, keys, whereClause, keyColumns);
    }

    /**
     * Build normalized SELECT query for specific primary keys.
     * 
     * This method generates a query similar to row-hash query, with normalized columns,
     * but filtered by specific primary keys. This ensures the data returned matches
     * the normalization used in hash calculation.
     * 
     * @param segment Table segment
     * @param primaryKeys Set of primary keys to query
     * @return SQL query string with normalized columns
     */
    protected String buildNormalizedQueryByKeys(TableSegment segment, Set<List<Object>> primaryKeys) {
        // Get schema and column information
        Optional<TableSchema> schemaOpt = segment.getSchema();
        if (!schemaOpt.isPresent()) {
            // Fallback to regular query if schema not available
            log.warn("Schema not available for segment, using regular query");
            return buildSelectQueryByKeys(segment, primaryKeys);
        }

        TableSchema schema = schemaOpt.get();
        List<String> keyColumns = segment.getKeyColumns();
        List<String> relevantColumns = segment.getRelevantColumns();
        
        // Build column data types map
        Map<String, DataType> columnDataTypes = new HashMap<>();
        for (String column : relevantColumns) {
            DataType dataType = schema.getColumnType(column);
            columnDataTypes.put(column, dataType);
        }
        
        List<String> selectColumns = new ArrayList<>();
        for (String column : relevantColumns) {
            DataType dataType = columnDataTypes.get(column);
            // Use DataTypeHandler to normalize the column (same as row-hash query)
            // Use original column names as aliases for compatibility
            selectColumns.add(dialect.getDataTypeHandler().normalizeColumn(column, dataType) + 
                    " AS " + quote(column));
        }
        String whereClause = segment.buildWhereClause();
        List<List<Object>> keys = new ArrayList<>(primaryKeys);

        String sql = selectByKeysSql(segment, selectColumns, keyColumns, keys, whereClause, keyColumns);

        log.debug("Generated normalized query by keys for table: {}, keys: {}", 
                segment.getDisplayName(), primaryKeys.size());
        
        return sql;
    }

    /**
     * Build normalized SELECT query for the given table segment (without key filtering).
     * Similar to buildNormalizedQueryByKeys but queries all rows in the segment.
     * 
     * This ensures NONE mode (full data comparison) also uses database-side normalization,
     * maintaining consistency across local comparison modes.
     */
    protected String buildNormalizedQuery(TableSegment segment) {
        // Get schema and column information
        Optional<TableSchema> schemaOpt = segment.getSchema();
        if (!schemaOpt.isPresent()) {
            // Fallback to regular query if schema not available
            log.warn("Schema not available for segment, using regular query");
            return buildSelectQuery(segment);
        }

        TableSchema schema = schemaOpt.get();
        List<String> keyColumns = segment.getKeyColumns();
        List<String> relevantColumns = segment.getRelevantColumns();
        
        // Build column data types map
        Map<String, DataType> columnDataTypes = new HashMap<>();
        for (String column : relevantColumns) {
            DataType dataType = schema.getColumnType(column);
            columnDataTypes.put(column, dataType);
        }
        
        List<String> selectColumns = new ArrayList<>();
        for (String column : relevantColumns) {
            DataType dataType = columnDataTypes.get(column);
            // Use DataTypeHandler to normalize the column (same as row-hash query)
            // Use original column names as aliases for compatibility
            selectColumns.add(dialect.getDataTypeHandler().normalizeColumn(column, dataType) + 
                    " AS " + quote(column));
        }

        String whereClause = segment.buildWhereClause();

        String sql = selectSql(segment, selectColumns, whereClause, keyColumns);
        
        log.debug("Generated normalized query for table: {}", 
                segment.getDisplayName());
        
        return sql;
    }


    /**
     * Build SELECT query for the given table segment.
     */
    protected String buildSelectQuery(TableSegment segment) {
        List<String> relevantColumns = segment.getRelevantColumns();
        List<String> keyColumns = segment.getKeyColumns();
        String whereClause = segment.buildWhereClause();
        return selectSql(segment, relevantColumns, whereClause, keyColumns);
    }

    /**
     * Extract key values from query result.
     */
    private List<Object> extractKeyValues(Object[] result, int startIndex, int count) {
        List<Object> keyValues = new ArrayList<>();
        int endIndex = Math.min(startIndex + count, result.length);

        for (int i = startIndex; i < endIndex; i++) {
            keyValues.add(result[i]);
        }

        return keyValues;
    }

    // Helper methods

    protected <T> RowMapper<T> createResultSetMapper(Class<T> resultType) {
        return (rs, rowNum) -> {
            try {
                if (resultType == String.class) {
                    return resultType.cast(rs.getString(1));
                } else if (resultType == Integer.class || resultType == int.class) {
                    return resultType.cast(rs.getInt(1));
                } else if (resultType == Long.class || resultType == long.class) {
                    return resultType.cast(rs.getLong(1));
                } else if (resultType == Double.class || resultType == double.class) {
                    return resultType.cast(rs.getDouble(1));
                } else if (resultType == Boolean.class || resultType == boolean.class) {
                    return resultType.cast(rs.getBoolean(1));
                } else if (resultType == java.sql.Date.class) {
                    return resultType.cast(rs.getDate(1));
                } else if (resultType == java.util.Date.class) {
                    return resultType.cast(rs.getTimestamp(1));
                } else {
                    return resultType.cast(rs.getObject(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error mapping result to type " + resultType, e);
            }
        };
    }

    protected void setParameters(PreparedStatement statement, Object... parameters) throws SQLException {
        if (parameters != null) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
        }
    }

    @Override
    public SqlQueryGenerator getSqlQueryGenerator() {
        return dialect.getSqlQueryGenerator();
    }

    protected <T> List<T> processResultSet(ResultSet resultSet, RowMapper<T> rowMapper) throws SQLException {
        List<T> results = new ArrayList<>();
        int rowNum = 0;
        while (resultSet.next()) {
            results.add(rowMapper.mapRow(resultSet, rowNum++));
        }
        return results;
    }

    protected void releaseQuietly(Connection connection) {
        if (connection != null) {
            try {
                connectionPool.releaseConnection(connection);
            } catch (Exception e) {
                log.debug("Error releasing connection", e);
            }
        }
    }

    // Note: DatabaseChecksumHelper will be implemented in the query package
    // For now, we'll use a simplified approach


    /**
     * Build the SQL query for row-hash based local comparison.
     */
    protected String buildRowHashQuery(TableSegment segment) {
        List<String> primaryKeys = segment.getKeyColumns();
        List<String> columns = segment.getRelevantColumns();
        String whereClause = segment.buildWhereClause();

        // Retrieve column data types
        Map<String, DataType> columnDataTypes = new HashMap<>();
        TableSchema tableSchema;
        if (segment.getSchema().isPresent()) {
            tableSchema = segment.getSchema().get();
        } else {
            if (segment.hasRelationSource()) {
                throw new IllegalStateException("Schema is required for SQL relation segment: " + segment.getDisplayName());
            }
            List<String> tablePathComponents = segment.getTablePath().getComponents();
            tableSchema = getTableSchema(tablePathComponents);
            segment.withSchema(tableSchema);
        }

        for (String column : columns) {
            DataType dataType = tableSchema.getColumnType(column);
            columnDataTypes.put(column, dataType);
        }

        // Log only table name, key/column counts, and where clause — not the full column list
        log.debug("Building row-hash query for table: {}, primaryKeys: {} columns, dataColumns: {} columns, where: {}",
                segment.getDisplayName(), primaryKeys.size(), columns.size(), whereClause != null ? "present" : "none");

        String sql = segment.hasRelationSource()
                ? dialect.getSqlQueryGenerator().getRowHashSQLFromSql(
                        segment.getRelationFromSql(),
                        primaryKeys,
                        columns,
                        columnDataTypes,
                        whereClause)
                : dialect.getSqlQueryGenerator().getRowHashSQL(
                        segment.getTablePath().getSchema().orElse(null),
                        segment.getTablePath().getTableName(),
                        primaryKeys,
                        columns,
                        columnDataTypes,
                        whereClause);
        log.info("Generated row-hash query: {}", sql);

        return sql;
    }

}
