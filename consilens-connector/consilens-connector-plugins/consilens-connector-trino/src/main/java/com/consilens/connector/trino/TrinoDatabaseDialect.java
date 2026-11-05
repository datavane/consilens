package com.consilens.connector.trino;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Trino database dialect implementation.
 */
public class TrinoDatabaseDialect extends AbstractDatabaseDialect {

    private final TrinoCapabilityProvider capabilityProvider;
    private final TrinoSqlQueryGenerator sqlQueryGenerator;
    private final TrinoMetadataQueryGenerator metadataQueryGenerator;
    private final TrinoDataTypeHandler dataTypeHandler;

    public TrinoDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Trino database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public TrinoDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new TrinoCapabilityProvider();
        this.dataTypeHandler = new TrinoDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new TrinoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new TrinoMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.TRINO;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "io.trino.jdbc.TrinoDriver";
    }

    @Override
    public int getDefaultPort() {
        return 8080;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        String database = params.get("database") != null ? String.valueOf(params.get("database")) : "";
        return "jdbc:trino://" + host + ":" + port + "/" + database;
    }

    @Override
    public DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return new TrinoDataSourceConfigBuilder();
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
}
