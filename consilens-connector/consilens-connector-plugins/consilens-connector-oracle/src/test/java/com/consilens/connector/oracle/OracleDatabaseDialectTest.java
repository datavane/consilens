package com.consilens.connector.oracle;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OracleDatabaseDialectTest {

    private OracleDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new OracleDatabaseDialect();
    }

    /**
     * Oracle 的方言侧能力调整（原先写在共享 base 里按连接器类型分支）：
     * SQL 侧摘要整体禁用（退本地比对），含 LOB 列的表再摘除 join。
     */
    @Test
    void testAdjustDatasetCapabilitiesKeepsJoinUnlessLobColumns() {
        EnumSet<ConnectorCapability> capabilities = EnumSet.of(
                ConnectorCapability.SERVER_SIDE_HASH, ConnectorCapability.SERVER_SIDE_JOIN);
        Map<String, Object> attributes = new HashMap<>();

        dialect.adjustDatasetCapabilities(capabilities, attributes, () -> false);

        assertFalse(capabilities.contains(ConnectorCapability.SERVER_SIDE_HASH));
        assertTrue(capabilities.contains(ConnectorCapability.SERVER_SIDE_JOIN));
        assertEquals(Boolean.TRUE, attributes.get("localCompareFallback"));
        assertNull(attributes.get("lobFallback"));

        EnumSet<ConnectorCapability> lobCapabilities = EnumSet.of(
                ConnectorCapability.SERVER_SIDE_HASH, ConnectorCapability.SERVER_SIDE_JOIN);
        Map<String, Object> lobAttributes = new HashMap<>();

        dialect.adjustDatasetCapabilities(lobCapabilities, lobAttributes, () -> true);

        assertFalse(lobCapabilities.contains(ConnectorCapability.SERVER_SIDE_JOIN));
        assertEquals(Boolean.TRUE, lobAttributes.get("lobFallback"));
    }

    @Test
    void testGetConnectorType() {
        assertEquals("oracle", dialect.getConnectorType());
    }

    @Test
    void testGetCapabilityProvider() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertNotNull(provider);
        assertEquals("\"", provider.getOpenQuote());
        assertEquals("\"", provider.getCloseQuote());
    }

    /**
     * 未加引号形式的标识符按 Oracle 语义折叠为大写；其它形式原样引用，
     * 已被双引号包裹的名字按"精确大小写"处理（可访问建表时就带引号的纯小写对象）。
     */
    @Test
    void testQuoteFoldsUnquotedIdentifiersOnly() {
        CapabilityProvider provider = dialect.getCapabilityProvider();

        assertEquals("\"USERS\"", provider.quote("users"));
        assertEquals("\"users\"", provider.quote("\"users\""));
        assertEquals("\"MyTable\"", provider.quote("MyTable"));
        assertEquals("\"Mixed_Case\"", provider.quote("Mixed_Case"));
        assertEquals("", provider.quote((String) null));
    }

    @Test
    void testSupportedFeatures() {
        CapabilityProvider provider = dialect.getCapabilityProvider();

        assertTrue(provider.supportsFeature(DatabaseFeature.JSON_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.WINDOW_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.TRANSACTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN));
    }

    @Test
    void testGetSqlQueryGenerator() {
        assertNotNull(dialect.getSqlQueryGenerator());
    }

    @Test
    void testGetMetadataQueryGenerator() {
        assertNotNull(dialect.getMetadataQueryGenerator());
    }

    @Test
    void testGetDataTypeHandler() {
        assertNotNull(dialect.getDataTypeHandler());
    }

    @Test
    void testGetTransactionManager() {
        assertNotNull(dialect.getTransactionManager());
    }
}
