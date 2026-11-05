package com.consilens.connector.oracle;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.connector.api.*;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.conncetor.base.AbstractDatabaseDialect;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Oracle database dialect implementation.
 */
public class OracleDatabaseDialect extends AbstractDatabaseDialect {

    private final OracleCapabilityProvider capabilityProvider;
    private final OracleSqlQueryGenerator sqlQueryGenerator;
    private final OracleMetadataQueryGenerator metadataQueryGenerator;
    private final OracleDataTypeHandler dataTypeHandler;
    private final OracleConnectionPoolOptimizer connectionPoolOptimizer;

    public OracleDatabaseDialect() {
        this(null);
    }
    
    /**
     * Constructs a new Oracle database dialect with normalization configuration.
     * 
     * @param normalizationConfig normalization configuration map
     */
    public OracleDatabaseDialect(Map<String, ?> normalizationConfig) {
        this.capabilityProvider = new OracleCapabilityProvider();
        this.dataTypeHandler = new OracleDataTypeHandler(capabilityProvider, normalizationConfig);
        this.sqlQueryGenerator = new OracleSqlQueryGenerator(capabilityProvider, dataTypeHandler);
        this.metadataQueryGenerator = new OracleMetadataQueryGenerator(capabilityProvider);
        this.connectionPoolOptimizer = new OracleConnectionPoolOptimizer();
    }

    @Override
    public String getConnectorType() {
        return ConnectorTypes.ORACLE;
    }

    /**
     * Oracle 在 SQL 侧做归一化/摘要有三条硬限制，故整体退到本地比对路径
     * （streaming-merge：JDBC 取回后在 Java 侧按同一口径归一化）：
     * <ol>
     *   <li>CLOB 无法参与：TO_CHAR(clob)&gt;4000 报 ORA-22835、STANDARD_HASH 不接受 CLOB
     *       （ORA-00902）、DBMS_CRYPTO 在目标库未授权；</li>
     *   <li>聚合摘要用 LISTAGG，返回 VARCHAR2（上限 4000 字节）→ 超过约 125 行的表
     *       直接 ORA-01489（实测 typediff 200 行失败）；</li>
     *   <li>两侧键的物理类型不同（NUMBER vs VARCHAR2）时，SQL 侧 ORDER BY 的排序语义
     *       与 Java 侧不一致会让归并错位。</li>
     * </ol>
     * 本地路径已与 SQL 路径统一口径（归一化 + 字符串序归并），并用 7 个 Oracle 任务验证。
     */
    @Override
    public void adjustDatasetCapabilities(EnumSet<ConnectorCapability> capabilities,
                                          Map<String, Object> attributes,
                                          BooleanSupplier hasLobColumns) {
        capabilities.remove(ConnectorCapability.SERVER_SIDE_HASH);
        // join 能力按表保留：无 LOB 列的表走 SQL 侧 join（键/比较表达式均为字符串归一化，
        // ORDER BY 已按字符串序对齐）；含 LOB/CLOB 的表 SQL 侧归一化不可行
        // （TO_CHAR(CLOB)>4000 → ORA-22835、STANDARD_HASH 拒绝 CLOB），仅这些表摘除 join。
        attributes.put("localCompareFallback", Boolean.TRUE);
        if (hasLobColumns.getAsBoolean()) {
            capabilities.remove(ConnectorCapability.SERVER_SIDE_JOIN);
            attributes.put("lobFallback", Boolean.TRUE);
        }
    }

    @Override
    public String getJdbcDriverClassName() {
        return "oracle.jdbc.OracleDriver";
    }

    @Override
    public int getDefaultPort() {
        return 1521;
    }

    @Override
    public String buildJdbcUrl(Map<String, Object> params) {
        String host = String.valueOf(params.get("host"));
        int port = params.get("port") != null ? ((Number) params.get("port")).intValue() : getDefaultPort();
        // 优先使用 sid 参数(Oracle service name),为空时回退 database 以兼容存量数据
        String service = params.get("sid") != null ? String.valueOf(params.get("sid")) : "";
        if (service.isEmpty() && params.get("database") != null) {
            service = String.valueOf(params.get("database"));
        }
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service;
    }

    @Override
    public DataSourceConfigBuilder getDataSourceConfigBuilder() {
        return new OracleDataSourceConfigBuilder();
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

    @Override
    public ConnectionPoolOptimizer getConnectionPoolOptimizer() {
        return connectionPoolOptimizer;
    }

    @Override
    public String getConnectionInitSql() {
        return OracleConnectionPoolOptimizer.DEFAULT_CONNECTION_INIT_SQL;
    }
}
