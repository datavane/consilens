package com.consilens.connector.postgresql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSQLMetadataQueryGenerator.
 */
class PostgreSQLMetadataQueryGeneratorTest {

    private PostgreSQLMetadataQueryGenerator generator;
    private PostgreSQLCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new PostgreSQLCapabilityProvider();
        generator = new PostgreSQLMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("public", "users");

        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("table_schema"));
        assertTrue(sql.contains("table_name"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("public", "users");

        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("column_name"));
        assertTrue(sql.contains("data_type"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("public", "users");

        assertTrue(sql.contains("information_schema"));
        assertTrue(sql.contains("PRIMARY KEY"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("public", "users");

        assertTrue(sql.contains("pg_index"));
        assertTrue(sql.contains("pg_attribute"));
        assertTrue(sql.contains("pg_namespace"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("information_schema.schemata"));
        assertTrue(sql.contains("schema_name"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("public");

        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("BASE TABLE"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("public", "users");

        assertTrue(sql.startsWith("ANALYZE"));
        assertTrue(sql.contains("\"public\".\"users\""));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("public", "users");

        assertTrue(sql.startsWith("VACUUM ANALYZE"));
        assertTrue(sql.contains("\"public\".\"users\""));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();

        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("version()") || sql.contains("VERSION()"));
    }

    @Test
    void testSQLInjectionProtection() {
        String sql = generator.getTableExistsSQL("test'; DROP TABLE users; --", "table");

        assertTrue(sql.contains("''") || sql.contains("\\'"));
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("public", "orders");

        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("referenced_table_name"));
    }
}
