package com.consilens.connector.trino;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrinoMetadataQueryGeneratorTest {

    private TrinoMetadataQueryGenerator generator;
    private TrinoCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new TrinoCapabilityProvider();
        generator = new TrinoMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("default", "users");

        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("table_schema"));
        assertTrue(sql.contains("table_name"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("default", "users");

        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("column_name"));
        assertTrue(sql.contains("data_type"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("default", "users");

        assertTrue(sql.contains("column_name"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("default", "users");

        assertTrue(sql.contains("index_name"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("information_schema.schemata"));
        assertTrue(sql.contains("schema_name"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("default");

        assertTrue(sql.contains("information_schema.tables"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("default", "users");

        assertTrue(sql.startsWith("ANALYZE"));
        assertTrue(sql.contains("\"default\".\"users\""));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("default", "users");

        assertTrue(sql.contains("No optimize"));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();

        assertTrue(sql.contains("SELECT 1"));
    }

    @Test
    void testSQLInjectionProtection() {
        String sql = generator.getTableExistsSQL("test'; DROP TABLE users; --", "table");

        assertTrue(sql.contains("''"));
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("default", "orders");

        assertTrue(sql.contains("constraint_name"));
    }
}
