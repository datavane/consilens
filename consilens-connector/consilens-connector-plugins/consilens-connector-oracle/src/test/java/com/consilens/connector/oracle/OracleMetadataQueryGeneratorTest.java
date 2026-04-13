package com.consilens.connector.oracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OracleMetadataQueryGeneratorTest {

    private OracleMetadataQueryGenerator generator;
    private OracleCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new OracleCapabilityProvider();
        generator = new OracleMetadataQueryGenerator(capabilityProvider);
    }

    @Test
    void testGetTableExistsSQL() {
        String sql = generator.getTableExistsSQL("SYSTEM", "users");

        assertTrue(sql.contains("ALL_TABLES"));
        assertTrue(sql.contains("OWNER"));
        assertTrue(sql.contains("TABLE_NAME"));
    }

    @Test
    void testGetTableColumnsSQL() {
        String sql = generator.getTableColumnsSQL("SYSTEM", "users");

        assertTrue(sql.contains("ALL_TAB_COLUMNS"));
        assertTrue(sql.contains("COLUMN_NAME"));
        assertTrue(sql.contains("DATA_TYPE"));
    }

    @Test
    void testGetPrimaryKeysSQL() {
        String sql = generator.getPrimaryKeysSQL("SYSTEM", "users");

        assertTrue(sql.contains("ALL_CONSTRAINTS"));
        assertTrue(sql.contains("CONSTRAINT_TYPE = 'P'"));
    }

    @Test
    void testGetIndexesSQL() {
        String sql = generator.getIndexesSQL("SYSTEM", "users");

        assertTrue(sql.contains("ALL_INDEXES"));
    }

    @Test
    void testGetSchemasSQL() {
        String sql = generator.getSchemasSQL();

        assertTrue(sql.contains("ALL_USERS"));
        assertTrue(sql.contains("USERNAME"));
    }

    @Test
    void testGetTablesSQL() {
        String sql = generator.getTablesSQL("SYSTEM");

        assertTrue(sql.contains("ALL_TABLES"));
    }

    @Test
    void testGetAnalyzeTableSQL() {
        String sql = generator.getAnalyzeTableSQL("SYSTEM", "users");

        assertTrue(sql.startsWith("ANALYZE TABLE"));
        assertTrue(sql.contains("COMPUTE STATISTICS"));
    }

    @Test
    void testGetOptimizeTableSQL() {
        String sql = generator.getOptimizeTableSQL("SYSTEM", "users");

        assertTrue(sql.startsWith("ALTER TABLE"));
        assertTrue(sql.contains("MOVE"));
    }

    @Test
    void testGetHealthCheckSQL() {
        String sql = generator.getHealthCheckSQL();

        assertTrue(sql.contains("SELECT 1"));
        assertTrue(sql.contains("V$VERSION"));
    }

    @Test
    void testSQLInjectionProtection() {
        String sql = generator.getTableExistsSQL("test'; DROP TABLE users; --", "table");

        assertTrue(sql.contains("''"));
    }

    @Test
    void testGetForeignKeysSQL() {
        String sql = generator.getForeignKeysSQL("SYSTEM", "orders");

        assertTrue(sql.contains("ALL_CONSTRAINTS"));
        assertTrue(sql.contains("REFERENCED_TABLE_NAME"));
    }
}
