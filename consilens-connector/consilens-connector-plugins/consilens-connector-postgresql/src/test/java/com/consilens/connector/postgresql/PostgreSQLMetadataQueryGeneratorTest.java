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
        // 约束名只在 schema 内唯一，join 必须带上 schema/table 限定，否则跨 schema 同名主键会串列
        assertTrue(sql.contains("kcu.constraint_schema = tc.constraint_schema"), sql);
        assertTrue(sql.contains("kcu.table_schema = tc.table_schema"), sql);
        assertTrue(sql.contains("kcu.table_name = tc.table_name"), sql);
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
    void testGetDatabaseListSQL() {
        String sql = generator.getDatabaseListSQL();

        assertTrue(sql.contains("pg_database"));
        assertTrue(sql.contains("datname"));
        assertTrue(sql.contains("datistemplate = false"));
        assertTrue(sql.contains("has_database_privilege"));
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
    void testBackslashInNameIsNotDoubled() {
        String sql = generator.getTablesSQL("odd\\schema");

        assertTrue(sql.contains("'odd\\schema'"), sql);
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("public", "orders");

        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("referenced_table_name"));
    }
}
