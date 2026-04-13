package com.consilens.connector.postgresql;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.enums.DatabaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSQLDatabaseDialect.
 */
class PostgreSQLDatabaseDialectTest {

    private PostgreSQLDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new PostgreSQLDatabaseDialect();
    }

    @Test
    void testGetDatabaseType() {
        assertEquals(DatabaseType.POSTGRESQL, dialect.getDatabaseType());
    }

    @Test
    void testGetCapabilityProvider() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertNotNull(provider);
        assertEquals("\"", provider.getOpenQuote());
        assertEquals("\"", provider.getCloseQuote());
    }

    @Test
    void testSupportedFeatures() {
        CapabilityProvider provider = dialect.getCapabilityProvider();

        // Supported features
        assertTrue(provider.supportsFeature(DatabaseFeature.JSON_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.WINDOW_FUNCTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.TRANSACTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.SAVEPOINTS));
        assertTrue(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)); // PostgreSQL DOES support FULL OUTER
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
