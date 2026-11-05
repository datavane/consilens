package com.consilens.connector.doris;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;
import com.consilens.conncetor.base.MySQLProtocolConnectionPoolOptimizer;

import java.util.Map;

/**
 * Doris database dialect implementation.
 * 
 * <p>
 * Provides Doris-specific implementations of all dialect components through
 * composition pattern:
 * <ul>
 * <li>{@link DorisCapabilityProvider} - Doris features and capabilities</li>
 * <li>{@link DorisSqlQueryGenerator} - Doris SQL generation</li>
 * <li>{@link DorisMetadataQueryGenerator} - Doris metadata queries</li>
 * <li>{@link DorisDataTypeHandler} - Doris data type handling</li>
 * </ul>
 * 
 * <p>
 * This dialect is typically created by {@link DorisDatabaseDialectProvider}
 * and discovered via JDK {@code ServiceLoader}.
 * 
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("LDORIS");
 * }</pre>
 * 
 * @since 1.0.0
 */
public class DorisDatabaseDialect extends AbstractDatabaseDialect {

    private final DorisCapabilityProvider capabilityProvider;
    private final DorisSqlQueryGenerator sqlQueryGenerator;
    private final DorisMetadataQueryGenerator metadataQueryGenerator;
    private final DorisDataTypeHandler dataTypeHandler;

    /**
     * Constructs a new Doris database dialect.
     * Initializes all Doris-specific components.
     */
    public DorisDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Doris database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public DorisDatabaseDialect(Map<String, ?> normalizationConfig) {
        // Initialize Doris-specific components
        this.capabilityProvider = new DorisCapabilityProvider();
        this.dataTypeHandler = new DorisDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new DorisSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new DorisMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.DORIS;
    }

    /**
     * Doris 表带 {@code PARTITION BY} 时，分区列可用于后续分区裁剪/分段，
     * 因此在建 metadata 时探测一次（base 只按本标志决定是否执行 SHOW CREATE TABLE）。
     */
    @Override
    public boolean discoversPartitionAttributes() {
        return true;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 9030;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /**
     * 校验和比较依赖 MD5(GROUP_CONCAT(...))，必须抬高 group_concat_max_len，
     * 否则行数一多拼接结果被静默截断，真实差异会被漏检。
     */
    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return new MySQLProtocolConnectionPoolOptimizer();
    }

    @Override
    public CapabilityProvider getCapabilityProvider() {
        return capabilityProvider;
    }

    @Override
    public SqlQueryGenerator getSqlQueryGenerator() {
        return sqlQueryGenerator;
    }

    @Override
    public MetadataQueryGenerator getMetadataQueryGenerator() {
        return metadataQueryGenerator;
    }

    @Override
    public DataTypeHandler getDataTypeHandler() {
        return dataTypeHandler;
    }

    // TransactionManager uses the default implementation from
    // AbstractDatabaseDialect
    // No need to override getTransactionManager()
}
