package com.consilens.connector.api;

import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.write.TableWriteCompiler;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Database dialect interface - main entry point for database-specific
 * operations.
 * 
 * <p>
 * This interface follows the composition pattern, providing access to
 * specialized
 * component interfaces through getter methods:
 * <ul>
 * <li>{@link SqlQueryGenerator} - SQL query generation</li>
 * <li>{@link MetadataQueryGenerator} - Metadata query generation</li>
 * <li>{@link DataTypeHandler} - Data type handling and conversion</li>
 * <li>{@link TransactionManager} - Transaction management</li>
 * <li>{@link CapabilityProvider} - Database capability detection</li>
 * </ul>
 * 
 * <p>
 * <b>Usage Example:</b>
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("mysql");
 * 
 * // Get SQL query component
 * SqlQueryGenerator queryGen = dialect.getSqlQueryGenerator();
 * String sql = queryGen.getCountSQL("myschema", "mytable", null);
 * 
 * // Get metadata component
 * MetadataQueryGenerator metadataGen = dialect.getMetadataQueryGenerator();
 * String existsSQL = metadataGen.getTableExistsSQL("myschema", "mytable");
 * }</pre>
 * 
 * @since 1.0.0
 */
public interface DatabaseDialect {

    // ========== Core Properties ==========

    /**
     * Get the connector type identifier this dialect supports (e.g. "mysql", "postgresql").
     *
     * @return lowercase connector type string
     */
    String getConnectorType();

    // ========== Component Access Methods ==========

    /**
     * Get the SQL query generator component.
     * 
     * @return SQL query generator for this dialect
     */
    SqlQueryGenerator getSqlQueryGenerator();

    /**
     * Get the metadata query generator component.
     * 
     * @return metadata query generator for this dialect
     */
    MetadataQueryGenerator getMetadataQueryGenerator();

    /**
     * Get the data type handler component.
     * 
     * @return data type handler for this dialect
     */
    DataTypeHandler getDataTypeHandler();

    /**
     * Get the transaction manager component.
     * 
     * @return transaction manager for this dialect
     */
    TransactionManager getTransactionManager();

    /**
     * Get the capability provider component.
     * 
     * @return capability provider for this dialect
     */
    CapabilityProvider getCapabilityProvider();

    /**
     * Get the connection pool optimizer component.
     * 
     * @return connection pool optimizer for this dialect
     */
    ConnectionPoolOptimizer getConnectionPoolOptimizer();

    // ========== JDBC Support (datasource management / connection test) ==========

    /**
     * JDBC driver class name for this connector type
     * (e.g. "com.mysql.cj.jdbc.Driver"). Used by the server's connection-test
     * and metadata-exploration flows. Returns {@code null} for connector types
     * without JDBC support.
     *
     * @return fully qualified driver class name, or null
     */
    default String getJdbcDriverClassName() {
        return null;
    }

    /**
     * Whether no-argument queries may go through the driver's server-side
     * prepare path. Presto's driver routes {@code prepareStatement(sql)} (no
     * parameters) through a server-side PREPARE + EXECUTE that can hang during
     * execution/fetch, so it returns {@code false}; the core adapter then
     * executes parameterless queries through a plain
     * {@link java.sql.Statement}. Queries with parameters always use a
     * {@code PreparedStatement}.
     *
     * @return true when parameterless queries may be prepared server-side
     */
    default boolean supportsServerSidePrepare() {
        return true;
    }

    /**
     * 方言侧对数据集体能力集/属性的调整入口，使 base 的 metadata 组装保持厂商中立。
     *
     * <p>默认不做调整。需要在某些方言上摘除能力（例如 SQL 侧摘要或 join 在含 LOB 的表上
     * 不可用）或补充属性时，由该方言覆盖本方法，base 不再按连接器类型做分支。
     *
     * @param capabilities  已按通用规则填好的能力集，可直接增删
     * @param attributes    将随 metadata 暴露的属性表，可直接增删
     * @param hasLobColumns 惰性 LOB 探测：仅在方言确实需要时才调用（每次调用触发一次元数据查询）
     */
    default void adjustDatasetCapabilities(EnumSet<ConnectorCapability> capabilities,
                                           Map<String, Object> attributes,
                                           BooleanSupplier hasLobColumns) {
    }

    /**
     * 是否需要探测数据集的物理分区属性（如 Doris 的 {@code PARTITION BY} 列），
     * 供分区裁剪/分段使用。默认不探测（会额外执行一次 {@code SHOW CREATE TABLE}）。
     */
    default boolean discoversPartitionAttributes() {
        return false;
    }

    /**
     * 是否可以用 JDBC {@link java.sql.DatabaseMetaData} 做 schema 发现。
     *
     * <p>绝大多数方言可以，且元数据比 result-set 更完整（能给出 DECIMAL 精度等）。
     * 但个别驱动的 {@code getColumns} 在并发查询下会**偶发挂起且无法设置超时**（典型：Presto），
     * 这类方言应返回 {@code false}，让 schema 发现直接走
     * {@code SELECT * FROM t WHERE 1=0} 的 result-set 路径。
     */
    default boolean supportsJdbcMetadata() {
        return true;
    }

    /**
     * 是否支持 ANSI 时间字面量（{@code DATE '2026-01-01'} / {@code TIMESTAMP '...'}）。
     * SQL Server 不接受这两种字面量，只接受可隐式转换的普通字符串；其余方言默认支持。
     *
     * @return true when ANSI temporal literals can be used in SQL
     */
    default boolean usesAnsiTemporalLiterals() {
        return true;
    }

    /**
     * SQL executed on every new pooled connection (session-level settings).
     * Oracle overrides this to pin deterministic NLS date/timestamp formats so
     * ORDER BY of temporal keys matches the Java-side normalized ordering.
     */
    default String getConnectionInitSql() {
        return null;
    }

    /**
     * 流式读取时是否必须关闭 autoCommit。默认保持驱动默认行为；
     * PostgreSQL 的游标只在事务内有效，需要显式事务才能流式 fetch。
     */
    default boolean requiresTransactionForStreamingReads() {
        return false;
    }

    /**
     * JSON/UUID 等结构化类型列写入时使用的 JDBC 类型。默认按字符串绑定；
     * PostgreSQL 驱动要求 {@link java.sql.Types#OTHER} 才会走 json/uuid 的类型编解码。
     */
    default int getStructuredColumnJdbcType() {
        return java.sql.Types.VARCHAR;
    }

    /**
     * Default connection port used when no explicit port is given.
     *
     * @return default port for this connector type
     */
    default int getDefaultPort() {
        return 3306;
    }

    /**
     * Build the JDBC URL for a connection. The {@code params} map uses these
     * keys (all optional except {@code host}):
     * <ul>
     * <li>{@code host} - resolved IP literal of the target (required)</li>
     * <li>{@code port} - explicit port; falls back to {@link #getDefaultPort()}</li>
     * <li>{@code database} - database / schema / service name</li>
     * </ul>
     * Returns {@code null} when the connector type cannot build a JDBC URL.
     *
     * @param params connection parameters
     * @return JDBC URL, or null when unsupported
     */
    default String buildJdbcUrl(Map<String, Object> params) {
        return null;
    }

    /**
     * Get the datasource parameter template builder for this connector type.
     * <p>
     * Returns {@code null} when the dialect has no dedicated template; the
     * server then falls back to the generic JDBC template
     * (host/port/database/username/password/properties).
     *
     * @return datasource parameter template builder, or null
     */
    default DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return null;
    }

    /**
     * Get the table sink write compiler for this connector type.
     *
     * @return table write compiler
     */
    TableWriteCompiler getTableWriteCompiler();
}
