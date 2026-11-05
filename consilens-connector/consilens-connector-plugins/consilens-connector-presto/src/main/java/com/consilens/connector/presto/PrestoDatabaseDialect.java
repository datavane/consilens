package com.consilens.connector.presto;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.Map;

/**
 * Presto database dialect implementation.
 */
public class PrestoDatabaseDialect extends AbstractDatabaseDialect {

    private final PrestoCapabilityProvider capabilityProvider;
    private final PrestoSqlQueryGenerator sqlQueryGenerator;
    private final PrestoMetadataQueryGenerator metadataQueryGenerator;
    private final PrestoDataTypeHandler dataTypeHandler;

    public PrestoDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Presto database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public PrestoDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new PrestoCapabilityProvider();
        this.dataTypeHandler = new PrestoDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new PrestoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new PrestoMetadataQueryGenerator(capabilityProvider);
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.PRESTO;
    }

    /**
     * Presto 的 {@code DatabaseMetaData.getColumns} 在并发查询下会偶发挂起（无超时可设），
     * 真机表现为 schema 发现阶段卡住 600s+（presto-dqcmp-03-edge 等任务 TIMEOUT），
     * 而服务端 {@code SHOW COLUMNS} 同样数据是毫秒级返回。这里改为走 result-set 发现。
     */
    @Override
    public boolean supportsJdbcMetadata() {
        return false;
    }

    @Override
    public String getJdbcDriverClassName() {
        return "io.prestosql.jdbc.PrestoDriver";
    }

    /**
     * 无参 prepareStatement 会走服务端 PREPARE + EXECUTE；实测该路径下执行/取数挂起
     * （服务端 PREPARE 已完成，客户端每 1~2 分钟重新提交一次，任务整体超时）。
     * 因此无参查询改用普通 Statement（见 AbstractDatabaseAdapter#query）。
     */
    @Override
    public boolean supportsServerSidePrepare() {
        return false;
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
        return "jdbc:presto://" + host + ":" + port + "/" + database;
    }

    @Override
    public DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return new PrestoDataSourceConfigBuilder();
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
