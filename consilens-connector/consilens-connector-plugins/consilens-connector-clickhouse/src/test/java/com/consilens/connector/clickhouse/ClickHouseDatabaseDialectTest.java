package com.consilens.connector.clickhouse;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClickHouseDatabaseDialect.
 */
class ClickHouseDatabaseDialectTest {

    private ClickHouseDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new ClickHouseDatabaseDialect();
    }

    @Test
    void testGetConnectorType() {
        assertEquals("clickhouse", dialect.getConnectorType());
    }

    @Test
    void testGetCapabilityProvider() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertNotNull(provider);
        assertEquals("`", provider.getOpenQuote()); // ClickHouse uses backticks
        assertEquals("`", provider.getCloseQuote());
    }

    @Test
    void testSupportedFeatures() {
        CapabilityProvider provider = dialect.getCapabilityProvider();

        // Supported features
        assertTrue(provider.supportsFeature(DatabaseFeature.JSON_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.WINDOW_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.TRANSACTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.SAVEPOINTS));
        assertTrue(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)); // ClickHouse DOES support FULL OUTER
                                                                               // JOIN
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
