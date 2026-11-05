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

import java.math.BigDecimal;

import java.sql.*;
import java.util.*;

/**
 * Abstract base class for database adapters providing common functionality.
 * Integrates best practices from all modules.
 */
@Slf4j
public abstract class AbstractDatabaseAdapter implements DatabaseAdapter {

    /**
     * 行哈希流式扫描的 JDBC fetch size：1000 行足以摊薄往返开销，又不会让
     * 驱动/服务端为单次批量预取过多行而抬高内存占用。
     */
    private static final int ROW_HASH_FETCH_SIZE = 1000;

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
        return query(sql, createResultSetMapper(resultType));
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
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection();
            statement = createQueryStatement(connection, sql, parameters);
            resultSet = statement instanceof PreparedStatement
                    ? ((PreparedStatement) statement).executeQuery()
                    : statement.executeQuery(sql);

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
            // Use TreeMap with case-insensitive comparator for cross-database compatibility
            // Oracle returns column labels in uppercase by default
            List<Map<String, Object>> results = query(checksumQuery, (rs, rowNum) -> {
                Map<String, Object> row = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
            log.error("Error getting count and bounds for segment {}: {}", segment.getTablePath(), e.getMessage());
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
        segment = resolveSegmentSchema(segment);
        TableSchema tableSchema = segment.getSchema().orElseThrow();
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
     * Get min or max key values for the segment.
     */
    private List<Object> getMinMaxKey(TableSegment segment, boolean getMin) {
        if (segment.getKeyColumns().isEmpty()) {
            return null;
        }

        String sql = segment.getKeyColumns().size() > 1
                ? buildCompositeBoundaryKeyQuery(segment, getMin)
                : buildSingleColumnBoundaryKeyQuery(segment, getMin);

        try {
            List<Object[]> results = query(sql, Object[].class);
            if (!results.isEmpty()) {
                Object[] row = results.get(0);
                List<String> keyColumns = segment.getKeyColumns();
                Optional<TableSchema> schema = segment.getSchema();
                List<Object> keyValues = new ArrayList<>(row.length);
                for (int i = 0; i < row.length; i++) {
                    DataType type = null;
                    if (schema.isPresent() && i < keyColumns.size()) {
                        type = schema.get().getColumnType(keyColumns.get(i));
                    }
                    keyValues.add(coerceBoundaryValue(row[i], type));
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
                log.warn("Error getting min/max key values even with single column approach: {}", e2.getMessage());
            }
        } catch (Exception e) {
            log.warn("Error getting min/max key values: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 边界值统一成可比较的类型。
     *
     * <p>边界查询通过 {@code Object[]} 映射器读取，所有列都被 {@code rs.getString} 转成字符串
     * （这是为了 BLOB/HEX 归一化值，见 {@code query(sql, Object[].class)}）。字符串边界的
     * 字典序与数值序不同：{@code "9999" > "20000"}，会让并集上界变小、整段数据从未被扫描。
     * 因此这里按键列类型把数值边界转成 {@link BigDecimal}，后续比较/格式化都按数值处理。
     */
    private static Object coerceBoundaryValue(Object value, DataType type) {
        if (value == null || type == null || !isNumericType(type)) {
            return value;
        }
        if (value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean isNumericType(DataType dataType) {
        switch (dataType) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case NUMERIC:
            case FLOAT:
            case DOUBLE:
            case REAL:
                return true;
            default:
                return false;
        }
    }

    private String appendNotNullKeys(List<String> keyColumns) {
        StringBuilder condition = new StringBuilder();
        for (String column : keyColumns) {
            condition.append(" AND ").append(quoteColumn(column)).append(" IS NOT NULL");
        }
        return condition.toString();
    }

    private String buildSingleColumnBoundaryKeyQuery(TableSegment segment, boolean getMin) {
        String whereClause = segment.buildWhereClause();
        return segment.hasRelationSource()
                ? dialect.getSqlQueryGenerator().getMinMaxKeySQLFromSql(
                segment.getRelationFromSql(), segment.getKeyColumns(), getMin, whereClause)
                : dialect.getSqlQueryGenerator().getMinMaxKeySQL(
                segment.getTablePath().getSchema().orElse(null),
                segment.getTablePath().getTableName(),
                segment.getKeyColumns(),
                getMin,
                whereClause);
    }

    private String buildCompositeBoundaryKeyQuery(TableSegment segment, boolean getMin) {
        StringBuilder sql = new StringBuilder();
        List<String> keyColumns = segment.getKeyColumns();
        String whereClause = segment.buildWhereClause();

        sql.append("SELECT ");
        sql.append(String.join(", ", keyColumns.stream().map(this::quoteColumn).toArray(String[]::new)));
        sql.append(" FROM ");
        sql.append(resolveRelationRef(segment));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        } else {
            sql.append(" WHERE 1 = 1");
        }

        // 边界键必须排除 NULL：NULL 无法用区间谓词表达，且各库对 NULL 的排序位置不同
        // （PostgreSQL 的 DESC 把 NULL 排最前），取到 NULL 行会让整段谓词恒假、差异全漏。
        sql.append(appendNotNullKeys(keyColumns));
        String direction = getMin ? "ASC" : "DESC";
        sql.append(" ORDER BY ");
        sql.append(String.join(", ",
                keyColumns.stream()
                        .map(column -> quoteColumn(column) + " " + direction)
                        .toArray(String[]::new)));
        sql.append(" ").append(dialect.getSqlQueryGenerator().getLimitClause(1));
        return sql.toString();
    }

    private String resolveRelationRef(TableSegment segment) {
        if (segment.hasRelationSource()) {
            return "(" + stripTrailingSemicolon(segment.getRelationFromSql()) + ") consilens_sql_source";
        }
        return String.join(".",
                segment.getTablePath().getPathComponents().stream()
                        .map(this::quoteColumn)
                        .toArray(String[]::new));
    }

    private String stripTrailingSemicolon(String sql) {
        String normalized = sql != null ? sql.trim() : "";
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
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

    /**
     * 唯一键探针（严格档）：按键表达式分组，列出 count>1 的键及两侧行数。
     * 键表达式与比对口径一致（归一化 + 二进制安全），因此"仅大小写/空白不同的键"
     * 也按契约算同一键。
     *
     * <p>探测失败（SQL 资源、方言不支持等）时抛出异常而非返回空结果：调用方必须能区分
     * "键唯一"与"根本没验证成"——严格档（validateUniqueKeys=true）据此明确失败，
     * 开放档（JoinDiffer）自行捕获并降级。
     */
    @Override
    public List<String> findDuplicateKeys(TableSegment segment) {
        Optional<TableSchema> schemaOpt = segment.getSchema();
        Map<String, DataType> keyTypes = new HashMap<>();
        if (schemaOpt.isPresent()) {
            for (String key : segment.getKeyColumns()) {
                DataType type = schemaOpt.get().getColumnType(key);
                if (type != null) {
                    keyTypes.put(key, type);
                }
            }
        }
        String schema = segment.getTablePath().getSchema().orElse(null);
        String table = segment.getTablePath().getTableName();
        String sql = dialect.getSqlQueryGenerator().getDuplicateKeyProbeSQL(
                schema, table, segment.getKeyColumns(), keyTypes, segment.buildWhereClause());
        log.info("Executing duplicate-key probe: {}", sql);
        List<Object[]> rows = query(sql, Object[].class);
        List<String> violations = new ArrayList<>();
        for (Object[] row : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.length - 1; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(row[i]);
            }
            sb.append(" => ").append(row[row.length - 1]).append(" 行");
            violations.add(sb.toString());
        }
        return violations;
    }

    @Override
    public Map<List<Object>, String> querySegmentRowHashes(TableSegment segment) {
        Map<List<Object>, String> rowHashes = new LinkedHashMap<>();
        forEachSegmentRowHash(segment, rowHashes::put);
        return rowHashes;
    }

    @Override
    public void forEachSegmentRowHash(TableSegment segment, RowHashConsumer consumer) {
        try {
            String rowHashQuery = buildRowHashQuery(segment);

            log.debug("Executing row hash query for segment: {}, keys: {}", 
                    segment.getTablePath(), segment.getKeyColumns().size());

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(rowHashQuery)) {
                statement.setFetchSize(ROW_HASH_FETCH_SIZE);
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
                        consumer.accept(primaryKey, rowHash);
                        rowCount++;
                    }

                    log.debug("Retrieved {} row hashes for segment: {}", rowCount, segment.getTablePath());
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

        String baseSelect = selectSql(segment, keyColumns, whereClause, null);

        String limitClause = dialect.getSqlQueryGenerator().getLimitClause(offset, limit);
        return baseSelect + nativeKeyOrderClause(keyColumns) + " " + limitClause;
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

        String baseSelect = selectSql(segment, keyColumns, whereClause, null);

        String limitClause = dialect.getSqlQueryGenerator().getLimitClause(0, limit);
        return baseSelect + nativeKeyOrderClause(keyColumns) + " " + limitClause;
    }

    /**
     * 采样必须按**键的原生顺序**排序：offset/keyset 采样的前提是"第 N 行"与键区间一一对应。
     * 不能复用 {@code getSelectSQL} 的字符串化 ORDER BY（那是本地归并比对的跨方言对齐口径），
     * 否则字典序会把边界键取成完全不同的行——实测 dq_cmp_scale_100k 因此把
     * [54994,100010] 切成 [100000,100010]+[100010,100010]，(54994,100000) 整段从未参与比对。
     */
    private String nativeKeyOrderClause(List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder(" ORDER BY ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                clause.append(", ");
            }
            clause.append(quoteColumn(keyColumns.get(i)));
        }
        return clause.toString();
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
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (String column : relevantColumns) {
            // SQL-source segments may carry the same column twice under different
            // identifier case (config key vs metadata fields); Oracle rejects the
            // duplicate alias with ORA-00960, so deduplicate case-insensitively.
            if (!emitted.add(column.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            DataType dataType = columnDataTypes.get(column);
            // Key columns must stay raw in the SELECT: range predicates and ORDER BY
            // reference the column name, and dialects like ClickHouse resolve a WHERE
            // reference to a same-named SELECT alias (string-cast key + numeric range
            // bound fails with NO_COMMON_TYPE). Keys are normalized Java-side.
            if (keyColumns.contains(column)) {
                selectColumns.add(quote(column) + " AS " + quote(column));
                continue;
            }
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
        return buildNormalizedQuery(segment, null);
    }

    /**
     * 与 {@link #buildNormalizedQuery(TableSegment)} 同形，额外拼接引擎生成的谓词
     * （如开放档 join 的"键重复"过滤器）。额外谓词不经用户 where 校验器（它是引擎构造的、
     * 含 EXISTS/子查询，校验器会拒绝函数调用）；用户 filter 仍走 segment.buildWhereClause() 校验。
     */
    protected String buildNormalizedQuery(TableSegment segment, String extraPredicate) {
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
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (String column : relevantColumns) {
            // Deduplicate case-insensitively (see buildNormalizedQueryByKeys).
            if (!emitted.add(column.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            DataType dataType = columnDataTypes.get(column);
            // Keep key columns raw so WHERE/ORDER BY never resolve to a normalized
            // alias (see buildNormalizedQueryByKeys); keys are normalized Java-side.
            if (keyColumns.contains(column)) {
                selectColumns.add(quote(column) + " AS " + quote(column));
                continue;
            }
            // Use DataTypeHandler to normalize the column (same as row-hash query)
            // Use original column names as aliases for compatibility
            selectColumns.add(dialect.getDataTypeHandler().normalizeColumn(column, dataType) +
                    " AS " + quote(column));
        }

        String whereClause = combinePredicates(segment.buildWhereClause(), extraPredicate);

        String sql = selectSql(segment, selectColumns, whereClause, keyColumns);
        
        log.debug("Generated normalized query for table: {}", 
                segment.getDisplayName());
        
        return sql;
    }

    private String combinePredicates(String whereClause, String extraPredicate) {
        boolean hasWhere = whereClause != null && !whereClause.trim().isEmpty();
        boolean hasExtra = extraPredicate != null && !extraPredicate.trim().isEmpty();
        if (hasWhere && hasExtra) {
            return "(" + whereClause + ") AND (" + extraPredicate + ")";
        }
        return hasWhere ? whereClause : (hasExtra ? extraPredicate : null);
    }

    @Override
    public List<Object[]> querySegmentWithPredicate(TableSegment segment, String extraPredicate) {
        try {
            String selectQuery = buildNormalizedQuery(segment, extraPredicate);
            log.debug("Executing normalized segment query with predicate: {}", selectQuery);
            return query(selectQuery, Object[].class);
        } catch (Exception e) {
            log.error("Error querying segment with predicate: {}", segment.getTablePath(), e);
            throw new RuntimeException("Segment query failed", e);
        }
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
        };
    }

    /**
     * 有参查询始终用 {@link PreparedStatement}；无参查询是否可走服务端 prepare
     * 由 {@link DatabaseDialect#supportsServerSidePrepare()} 决定（Presto 返回 false，
     * 改用普通 {@link Statement}，原因见其方言实现）。
     */
    private Statement createQueryStatement(Connection connection, String sql, Object[] parameters)
            throws SQLException {
        if (parameters != null && parameters.length > 0) {
            PreparedStatement prepared = connection.prepareStatement(sql);
            setParameters(prepared, parameters);
            return prepared;
        }
        if (!dialect.supportsServerSidePrepare()) {
            return connection.createStatement();
        }
        return connection.prepareStatement(sql);
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
     * 取出 segment 已携带的 schema；没有则查一次元数据并回填到返回的副本上。
     * 注意 withSchema 返回副本而非原地修改，必须接收返回值，否则 schema 丢失、
     * 同一 segment 后续每次构建查询都会重复查元数据。
     */
    private TableSegment resolveSegmentSchema(TableSegment segment) {
        if (segment.getSchema().isPresent()) {
            return segment;
        }
        if (segment.hasRelationSource()) {
            throw new IllegalStateException("Schema is required for SQL relation segment: " + segment.getDisplayName());
        }
        List<String> tablePathComponents = segment.getTablePath().getComponents();
        return segment.withSchema(getTableSchema(tablePathComponents));
    }

    /**
     * Build the SQL query for row-hash based local comparison.
     */
    protected String buildRowHashQuery(TableSegment segment) {
        List<String> primaryKeys = segment.getKeyColumns();
        List<String> columns = segment.getRelevantColumns();
        String whereClause = segment.buildWhereClause();

        // Retrieve column data types
        Map<String, DataType> columnDataTypes = new HashMap<>();
        segment = resolveSegmentSchema(segment);
        TableSchema tableSchema = segment.getSchema().orElseThrow();

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
