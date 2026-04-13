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
    private final Map<String, Long> countCache = new HashMap<>();

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
            DatabaseMetaData metaData = conn.getMetaData();
            boolean tableExists = false;
            try (ResultSet tables = metaData.getTables(null, schemaName, tableName, new String[]{"TABLE"})) {
                tableExists = tables.next();
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
                    
                    // Log type conversion at DEBUG level
                    log.debug("Column '{}': JDBC TYPE_NAME='{}' -> DataType.{}", 
                            columnName, columnType, dataType);

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

    /**
     * Create DataType from dialect-mapped type information.
     * Uses the dialect's getDataTypeMapping method for database-specific type
     * mapping.
     */
    private DataType createDataTypeFromMappedType(String mappedType) {
        // Extract base type name without parameters (e.g., "VARCHAR(50)" -> "VARCHAR",
        // "DECIMAL(12,2)" -> "DECIMAL")
        String baseType = mappedType;
        int parenIndex = mappedType.indexOf('(');
        if (parenIndex > 0) {
            baseType = mappedType.substring(0, parenIndex).trim();
        }

        // Map dialect types to core DataType enum - simplified since dialect already
        // did the heavy lifting
        switch (baseType.toUpperCase()) {
            case "VARCHAR":
            case "VARCHAR2":
            case "TEXT":
            case "LONGVARCHAR":
            case "LONG VARCHAR":
            case "STRING": // BigQuery
                return DataType.VARCHAR;
            case "CHAR":
            case "CHARACTER":
                return DataType.CHAR;
            case "CLOB":
            case "LONGTEXT": // MySQL
            case "MEDIUMTEXT": // MySQL
            case "TINYTEXT": // MySQL
                return DataType.CLOB;
            case "INTEGER":
            case "INT":
            case "MEDIUMINT":
            case "INT4": // PostgreSQL
            case "INT64": // BigQuery
                return DataType.INTEGER;
            case "SMALLINT":
            case "INT2":
                return DataType.SMALLINT;
            case "TINYINT":
                return DataType.TINYINT;
            case "BIGINT":
            case "INT8":
                return DataType.BIGINT;
            case "FLOAT":
            case "FLOAT4":
            case "FLOAT64": // BigQuery
                return DataType.FLOAT;
            case "REAL":
                return DataType.REAL;
            case "DOUBLE":
            case "DOUBLE PRECISION":
            case "FLOAT8":
                return DataType.DOUBLE;
            case "DECIMAL":
            case "NUMERIC":
            case "BIGNUMERIC": // BigQuery
                return DataType.DECIMAL;
            case "DATE":
                return DataType.DATE;
            case "TIME":
                return DataType.TIME;
            case "TIMESTAMP":
            case "DATETIME":
            case "TIMESTAMPTZ": // PostgreSQL
                return DataType.DATETIME;
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP_TZ":
                return DataType.TIMESTAMP;
            case "BOOLEAN":
            case "BOOL":
                return DataType.BOOLEAN;
            case "BIT":
                return DataType.BIT;
            case "BLOB":
            case "BYTES": // BigQuery
                return DataType.BLOB;
            case "LONGBLOB":
            case "MEDIUMBLOB": // MySQL
            case "TINYBLOB": // MySQL
            case "LONG VARBINARY":
                return DataType.LONGBLOB;
            case "BINARY":
                return DataType.BINARY;
            case "VARBINARY":
            case "BYTEA": // PostgreSQL
                return DataType.VARBINARY;
            case "JSON":
            case "JSONB": // PostgreSQL
                return DataType.JSON;
            default:
                log.warn("Unknown mapped type: {}, defaulting to VARCHAR", mappedType);
                return DataType.VARCHAR;
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

            log.info("Executing count query: {}", countSql);

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
