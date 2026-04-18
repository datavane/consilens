package com.consilens.connector.mysql;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MySQLDatabaseDialect.
 */
class MySQLDatabaseDialectTest {

    private MySQLDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new MySQLDatabaseDialect();
    }

    @Test
    void testGetConnectorType() {
        assertEquals("mysql", dialect.getConnectorType());
    }

    @Test
    void testGetCapabilityProvider() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertNotNull(provider);
        assertEquals("`", provider.getOpenQuote());
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

        // Not supported
        assertFalse(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN));
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
