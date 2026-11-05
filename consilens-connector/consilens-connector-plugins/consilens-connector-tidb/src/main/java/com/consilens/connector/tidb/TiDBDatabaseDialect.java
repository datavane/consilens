package com.consilens.connector.tidb;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;
import com.consilens.conncetor.base.MySQLProtocolConnectionPoolOptimizer;

import java.util.Map;

/**
 * TiDB database dialect implementation.
 * 
 * <p>
 * Provides TiDB-specific components for SQL generation, metadata retrieval,
 * and data type handling.
 * </p>
 * 
 * @since 1.0.0
 */
public class TiDBDatabaseDialect extends AbstractDatabaseDialect {

    private final TiDBCapabilityProvider capabilityProvider;
    private final TiDBDataTypeHandler dataTypeHandler;
    private final TiDBSqlQueryGenerator sqlQueryGenerator;
    private final TiDBMetadataQueryGenerator metadataQueryGenerator;
    
    /**
     * Constructs a new TiDB database dialect.
     */
    public TiDBDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new TiDB database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public TiDBDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new TiDBCapabilityProvider();
        this.dataTypeHandler = new TiDBDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new TiDBSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new TiDBMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.TIDB;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 4000;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
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
}
