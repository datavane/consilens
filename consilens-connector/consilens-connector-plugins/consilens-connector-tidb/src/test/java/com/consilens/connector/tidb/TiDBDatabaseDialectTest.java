package com.consilens.connector.tidb;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiDBDatabaseDialectTest {

    private TiDBDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new TiDBDatabaseDialect();
    }

    @Test
    void shouldExposeTiDBComponentsAndCapabilities() {
        assertEquals("tidb", dialect.getConnectorType());
        assertNotNull(dialect.getSqlQueryGenerator());
        assertNotNull(dialect.getMetadataQueryGenerator());
        assertNotNull(dialect.getDataTypeHandler());
        assertNotNull(dialect.getTransactionManager());
        assertNotNull(dialect.getConnectionPoolOptimizer());

        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertEquals("`", provider.getOpenQuote());
        assertEquals("`", provider.getCloseQuote());
        assertTrue(provider.supportsFeature(DatabaseFeature.TRANSACTIONS));
        assertTrue(provider.supportsFeature(DatabaseFeature.WINDOW_FUNCTIONS));
        assertFalse(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN));
        assertFalse(provider.supportsFeature(DatabaseFeature.SAVEPOINTS));
    }
}
