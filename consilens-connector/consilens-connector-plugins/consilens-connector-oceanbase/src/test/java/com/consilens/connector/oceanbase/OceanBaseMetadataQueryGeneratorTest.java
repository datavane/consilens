package com.consilens.connector.oceanbase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OceanBaseMetadataQueryGenerator.
 */
class OceanBaseMetadataQueryGeneratorTest {

    private OceanBaseMetadataQueryGenerator generator;
    private OceanBaseCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new OceanBaseCapabilityProvider();
        generator = new OceanBaseMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("test_db", "test_table");
        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("test_db"));
        assertTrue(sql.contains("test_table"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("test_db", "test_table");
        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("column_name"));
        assertTrue(sql.contains("data_type"));
        assertTrue(sql.contains("ORDER BY ordinal_position"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("test_db", "test_table");
        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("constraint_name = 'PRIMARY'"));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();
        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("VERSION()"));
    }

    @Test
    void testGetDatabaseMetadataSQL() {
        String sql = generator.getDatabaseMetadataSQL();
        assertTrue(sql.contains("VERSION()"));
        assertTrue(sql.contains("DATABASE()"));
        assertTrue(sql.contains("USER()"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();
        assertTrue(sql.contains("information_schema.schemata"));
        // Verify OceanBase internal schemas are excluded
        assertTrue(sql.contains("OCEANBASE"));
        assertTrue(sql.contains("LBACSYS"));
        assertTrue(sql.contains("ORAAUDITOR"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("test_db");
        assertTrue(sql.contains("information_schema.tables"));
        assertTrue(sql.contains("test_db"));
        assertTrue(sql.contains("BASE TABLE"));
    }

    @Test
    void testGetViewsSQL() {
        String sql = generator.getViewsSQL("test_db");
        assertTrue(sql.contains("information_schema.views"));
        assertTrue(sql.contains("test_db"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("test_db", "test_table");
        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("index_name"));
        assertTrue(sql.contains("column_name"));
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("test_db", "test_table");
        assertTrue(sql.contains("information_schema.key_column_usage"));
        assertTrue(sql.contains("referenced_table_name IS NOT NULL"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("test_db", "test_table");
        assertTrue(sql.contains("ANALYZE TABLE"));
        assertTrue(sql.contains("`test_db`"));
        assertTrue(sql.contains("`test_table`"));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("test_db", "test_table");
        // OceanBase uses ANALYZE TABLE for statistics update
        assertTrue(sql.contains("ANALYZE TABLE"));
    }
}
