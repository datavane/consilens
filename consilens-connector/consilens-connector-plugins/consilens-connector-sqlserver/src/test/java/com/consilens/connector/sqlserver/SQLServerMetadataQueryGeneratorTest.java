package com.consilens.connector.sqlserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SQLServerMetadataQueryGenerator.
 */
class SQLServerMetadataQueryGeneratorTest {

    private SQLServerMetadataQueryGenerator generator;
    private SQLServerCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new SQLServerCapabilityProvider();
        generator = new SQLServerMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("dbo", "users");

        assertTrue(sql.contains("INFORMATION_SCHEMA.TABLES"));
        assertTrue(sql.contains("TABLE_SCHEMA"));
        assertTrue(sql.contains("TABLE_NAME"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("dbo", "users");

        assertTrue(sql.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(sql.contains("COLUMN_NAME"));
        assertTrue(sql.contains("DATA_TYPE"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("dbo", "users");

        assertTrue(sql.contains("INFORMATION_SCHEMA"));
        assertTrue(sql.contains("PRIMARY KEY"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("dbo", "users");

        assertTrue(sql.contains("sys.indexes"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("INFORMATION_SCHEMA.SCHEMATA"));
        assertTrue(sql.contains("SCHEMA_NAME"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("dbo");

        assertTrue(sql.contains("INFORMATION_SCHEMA.TABLES"));
        assertTrue(sql.contains("BASE TABLE"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("dbo", "users");

        assertTrue(sql.startsWith("UPDATE STATISTICS"));
        assertTrue(sql.contains("[dbo].[users]"));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("dbo", "users");

        assertTrue(sql.startsWith("ALTER INDEX"));
        assertTrue(sql.contains("REBUILD"));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();

        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("@@VERSION"));
    }

    @Test
    void testSQLInjectionProtection() {
        String sql = generator.getTableExistsSQL("test'; DROP TABLE users; --", "table");

        assertTrue(sql.contains("''"));
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("dbo", "orders");

        assertTrue(sql.contains("sys.foreign_keys"));
        assertTrue(sql.contains("referenced_table_name"));
    }
}
