package com.consilens.connector.clickhouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClickHouseMetadataQueryGenerator.
 */
class ClickHouseMetadataQueryGeneratorTest {

    private ClickHouseMetadataQueryGenerator generator;
    private ClickHouseCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new ClickHouseCapabilityProvider();
        generator = new ClickHouseMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("public", "users");

        assertTrue(sql.contains("system.tables"));
        assertTrue(sql.contains("database"));
        assertTrue(sql.contains("name"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("public", "users");

        assertTrue(sql.contains("system.columns"));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("type"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("public", "users");

        assertTrue(sql.contains("system.columns"));
        assertTrue(sql.contains("is_in_primary_key"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("public", "users");

        assertTrue(sql.contains("system.data_skipping_indices"));
        assertTrue(sql.contains("index_name"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("system.databases"));
        assertTrue(sql.contains("name"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("public");

        assertTrue(sql.contains("system.tables"));
        assertTrue(sql.contains("database"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("public", "users");

        assertTrue(sql.contains("ClickHouse automatically"));
        assertTrue(sql.contains("table statistics"));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("public", "users");

        assertTrue(sql.startsWith("OPTIMIZE TABLE"));
        assertTrue(sql.contains("`public`.`users`"));
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

        assertTrue(sql.contains("system.tables"));
        assertTrue(sql.contains("referenced_table_name"));
    }
}
