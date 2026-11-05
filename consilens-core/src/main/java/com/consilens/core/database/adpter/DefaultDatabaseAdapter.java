package com.consilens.core.database.adpter;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of AbstractDatabaseAdapter.
 * Provides basic functionality that can be used across all modules.
 * Simplified version to avoid compilation issues.
 */
@Slf4j
public class DefaultDatabaseAdapter extends AbstractDatabaseAdapter {

    private final String url;
    
    // Cache for COUNT query results to avoid redundant database calls
    // Key: COUNT SQL query, Value: row count
    // count() 会被多个段/多个线程同时调用：HashMap 并发 put 在扩容时可能丢数据甚至成环
    private final Map<String, Long> countCache = new ConcurrentHashMap<>();

    public DefaultDatabaseAdapter(String name, ConnectionPool connectionPool, DatabaseDialect dialect, String url, ChecksumAlgorithm checksumAlgorithm) {
        super(name, connectionPool, dialect, checksumAlgorithm);
        this.url = url;
    }

    @Override
    public TableSchema getTableSchema(List<String> tablePath) {
        try (Connection conn = getConnection()) {
            String tableName = tablePath.get(tablePath.size() - 1);
            String schemaName = tablePath.size() > 1 ? tablePath.get(tablePath.size() - 2) : null;

            // Create TablePath
            TablePath path;
            if (schemaName != null) {
                path = TablePath.of(schemaName, tableName);
            } else {
                path = TablePath.of(tableName);
            }

            // First check if table exists
            // Use JDBC metadata first, but fall back to dialect-specific query
            // because some JDBC drivers (e.g., ClickHouse 0.4.6) have bugs in getTables()
            boolean tableExists = false;
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet tables = metaData.getTables(null, schemaName, tableName, new String[]{"TABLE"})) {
                tableExists = tables.next();
            }

            // Fallback: if JDBC metadata says table doesn't exist, try dialect-specific query
            // This handles cases where JDBC driver metadata is buggy (e.g., ClickHouse)
            if (!tableExists) {
                String checkSql = dialect.getMetadataQueryGenerator().getTableExistsSQL(schemaName, tableName);
                log.debug("JDBC metadata did not find table {}.{}, trying dialect-specific query: {}", schemaName, tableName, checkSql);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(checkSql)) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        tableExists = true;
                        log.debug("Dialect-specific query confirmed table {}.{} exists", schemaName, tableName);
                    }
                } catch (SQLException fallbackEx) {
                    log.debug("Dialect-specific table check also failed: {}", fallbackEx.getMessage());
                }
            }

            if (!tableExists) {
                String fullTableName = schemaName != null ? schemaName + "." + tableName : tableName;
                log.error("表不存在: {}", fullTableName);
                throw new RuntimeException("表不存在: " + fullTableName);
            }

            // Get column information
            try (ResultSet columns = metaData.getColumns(null, schemaName, tableName, null)) {
                Map<String, ColumnInfo> columnMap = new LinkedHashMap<>();

                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String columnType = columns.getString("TYPE_NAME");
                    int columnSize = columns.getInt("COLUMN_SIZE");
                    int precision = columns.getInt("COLUMN_SIZE");
                    int scale = columns.getInt("DECIMAL_DIGITS");
                    boolean nullable = columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    String defaultValue = columns.getString("COLUMN_DEF");
                    int ordinalPosition = columns.getInt("ORDINAL_POSITION");

                    // Special handling for MySQL TINYINT(1) as BOOLEAN
                    // MySQL JDBC driver returns "TINYINT" for BOOLEAN columns
                    if ("TINYINT".equalsIgnoreCase(columnType) && columnSize == 1) {
                        columnType = "BOOLEAN";
                    }

                    // Use dialect's type mapping instead of manual mapping
                    DataType dataType = dialect.getDataTypeHandler().convertToDataType(columnType);

                    // For Oracle NUMBER type, JDBC driver returns just "NUMBER" without precision/scale,
                    // so convertToDataType returns DECIMAL. Refine using precision/scale from metadata.
                    if ("NUMBER".equalsIgnoreCase(columnType) && scale == 0 && precision > 0) {
                        if (precision <= 9) {
                            dataType = DataType.INTEGER;
                        } else if (precision <= 18) {
                            dataType = DataType.BIGINT;
                        }
                    }

                    // Log type conversion at DEBUG level
                    log.debug("Column '{}': JDBC TYPE_NAME='{}' (precision={}, scale={}) -> DataType.{}",
                            columnName, columnType, precision, scale, dataType);

                    ColumnInfo columnInfo = ColumnInfo.builder()
                            .name(columnName)
                            .type(dataType)
                            .nullable(nullable)
                            .precision(Optional.of(precision))
                            .scale(Optional.of(scale))
                            .maxLength(Optional.of(columnSize))
                            .defaultValue(Optional.ofNullable(defaultValue))
                            .ordinalPosition(ordinalPosition)
                            .collation(Optional.empty())
                            .comment(Optional.empty())
                            .primaryKey(false)
                            .uniqueKey(false)
                            .build();

                    columnMap.put(columnName, columnInfo);
                }

                if (columnMap.isEmpty()) {
                    String fullTableName = schemaName != null ? schemaName + "." + tableName : tableName;
                    log.error("表 {} 没有列信息", fullTableName);
                    throw new RuntimeException("表 " + fullTableName + " 没有列信息");
                }

                log.debug("成功获取表 {} 的结构,共 {} 列", path, columnMap.size());
                return new TableSchema(path, columnMap);
            }
        } catch (SQLException e) {
            log.error("获取表结构失败: {}", tablePath, e);
            throw new RuntimeException("获取表结构失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long count(TableSegment segment) {
        try {
            String countSql = buildCountQuery(segment);

            // Check cache first
            if (countCache.containsKey(countSql)) {
                long cachedCount = countCache.get(countSql);
                log.debug("Using cached count result for query: {} => {}", countSql, cachedCount);
                return cachedCount;
            }

            log.debug("Executing count query: {}", countSql);

            Optional<Long> result = queryForObject(countSql, Long.class);
            long count = result.orElse(0L);
            
            // Cache the result
            countCache.put(countSql, count);
            
            return count;

        } catch (Exception e) {
            log.error("Error counting rows in segment: {}", segment.getTablePath(), e);
            throw new RuntimeException("Count operation failed", e);
        }
    }

    /**
     * Convert a value using the dialect's value conversion capabilities.
     * This method demonstrates how to use dialect-specific value conversion.
     */
    public Object convertValueUsingDialect(Object value, String targetType) {
        if (value == null) {
            return null;
        }
        return dialect.getDataTypeHandler().convertValue(value, targetType);
    }

    /**
     * Get table existence check SQL using dialect.
     */
    public String getTableExistsSQL(String schemaName, String tableName) {
        return dialect.getMetadataQueryGenerator().getTableExistsSQL(schemaName, tableName);
    }

    /**
     * Get health check SQL using dialect.
     */
    public String getHealthCheckSQL() {
        return dialect.getMetadataQueryGenerator().getHealthCheckSQL();
    }
}
