package com.consilens.connector.oceanbase;

import com.consilens.connector.api.*;
import com.consilens.connector.api.enums.DatabaseFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OceanBaseDatabaseDialect.
 */
class OceanBaseDatabaseDialectTest {

    private OceanBaseDatabaseDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new OceanBaseDatabaseDialect();
    }

    @Test
    void testGetConnectorType() {
        assertEquals("oceanbase", dialect.getConnectorType());
    }

    @Test
    void testGetCapabilityProvider() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertNotNull(provider);
        assertTrue(provider instanceof OceanBaseCapabilityProvider);
    }

    @Test
    void testGetSqlQueryGenerator() {
        SqlQueryGenerator generator = dialect.getSqlQueryGenerator();
        assertNotNull(generator);
        assertTrue(generator instanceof OceanBaseSqlQueryGenerator);
    }

    @Test
    void testGetMetadataQueryGenerator() {
        MetadataQueryGenerator generator = dialect.getMetadataQueryGenerator();
        assertNotNull(generator);
        assertTrue(generator instanceof OceanBaseMetadataQueryGenerator);
    }

    @Test
    void testGetDataTypeHandler() {
        DataTypeHandler handler = dialect.getDataTypeHandler();
        assertNotNull(handler);
        assertTrue(handler instanceof OceanBaseDataTypeHandler);
    }

    @Test
    void testGetConnectionPoolOptimizer() {
        ConnectionPoolOptimizer optimizer = dialect.getConnectionPoolOptimizer();
        assertNotNull(optimizer);
        assertTrue(optimizer instanceof OceanBaseConnectionPoolOptimizer);
    }

    @Test
    void testWithNormalizationConfig() {
        dialect = new OceanBaseDatabaseDialect(Map.of());
        assertNotNull(dialect.getCapabilityProvider());
        assertNotNull(dialect.getSqlQueryGenerator());
        assertNotNull(dialect.getMetadataQueryGenerator());
        assertNotNull(dialect.getDataTypeHandler());
        assertNotNull(dialect.getConnectionPoolOptimizer());
    }

    @Test
    void testSupportsFullOuterJoin() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN),
                "OceanBase should support FULL OUTER JOIN natively");
    }

    @Test
    void testSupportsCTE() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.CTE),
                "OceanBase should support CTE");
    }

    @Test
    void testSupportsWindowFunctions() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.WINDOW_FUNCTIONS));
    }

    @Test
    void testSupportsTransactions() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.TRANSACTIONS));
    }

    @Test
    void testSupportsSavepoints() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.SAVEPOINTS));
    }

    @Test
    void testSupportsStoredProcedures() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.STORED_PROCEDURES));
    }

    @Test
    void testSupportsCheckConstraints() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.CHECK_CONSTRAINTS));
    }

    @Test
    void testSupportsPartitioning() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.PARTITIONING));
    }

    @Test
    void testSupportsMaterializedViews() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.MATERIALIZED_VIEWS));
    }

    @Test
    void testSupportsRecursiveQueries() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertTrue(provider.supportsFeature(DatabaseFeature.RECURSIVE_QUERIES));
    }

    @Test
    void testIdentifierQuoting() {
        CapabilityProvider provider = dialect.getCapabilityProvider();
        assertEquals("`", provider.getOpenQuote());
        assertEquals("`", provider.getCloseQuote());
        assertEquals("`test_column`", provider.quote("test_column"));
    }
}
