package com.consilens.connector.doris;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DorisMetadataQueryGenerator.
 */
class DorisMetadataQueryGeneratorTest {

    private DorisMetadataQueryGenerator generator;
    private DorisCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new DorisCapabilityProvider();
        generator = new DorisMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("test_db", "users");

        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("table_schema"));
        assertTrue(sql.contains("table_name"));
        assertTrue(sql.contains("test_db"));
        assertTrue(sql.contains("users"));
    }

    @Test
    void testGetTableColumnsSQLWithEscaping() {
        String sql = generator.getTableColumnsSQL("test_db", "user's_table");

        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("column_name"));
        assertTrue(sql.contains("data_type"));
        // Should escape single quotes
        assertTrue(sql.contains("user''s_table") || sql.contains("user\\'s_table"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("test_db", "users");

        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("PRIMARY"));
        assertTrue(sql.contains("ordinal_position"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("test_db", "users");

        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("index_name"));
        assertTrue(sql.contains("non_unique"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("information_schema.schemata"));
        assertTrue(sql.contains("schema_name"));
        // Should exclude system schemas
        assertTrue(sql.toLowerCase().contains("where") || sql.toLowerCase().contains("not in"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("test_db");

        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("BASE TABLE"));
        assertTrue(sql.contains("test_db"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("test_db", "users");

        assertTrue(sql.startsWith("ANALYZE TABLE"));
        assertTrue(sql.contains("`test_db`.`users`"));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("test_db", "users");

        assertTrue(sql.startsWith("OPTIMIZE TABLE"));
        assertTrue(sql.contains("`test_db`.`users`"));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();

        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("version()") || sql.contains("VERSION()"));
    }

    @Test
    void testSQLInjectionProtection() {
        // Test that single quotes are escaped
        String sql = generator.getTableExistsSQL("test'; DROP TABLE users; --", "table");

        // Should escape the malicious input - single quotes should be doubled
        assertTrue(sql.contains("''") || sql.contains("\\'"));
        // The escaped version is safe even if it contains the keywords
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("test_db", "orders");

        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("referenced_table_name"));
        assertTrue(sql.contains("IS NOT NULL"));
    }
}
