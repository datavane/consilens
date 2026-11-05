package com.consilens.connector.tidb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TiDBMetadataQueryGeneratorTest {

    private TiDBMetadataQueryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TiDBMetadataQueryGenerator(new TiDBCapabilityProvider());
    }

    @Test
    void shouldGenerateCompleteMetadataSqlContract() {
        assertContains(generator.getTableExistsSQL("test_db", "orders"),
                "information_schema.tables", "test_db", "orders");
        assertContains(generator.getColumnExistsSQL("test_db", "orders", "amount"),
                "information_schema.columns", "column_name = ?");
        assertContains(generator.getPrimaryKeySQL("test_db", "orders"), "key_column_usage");
        assertContains(generator.getTableColumnsSQL("test_db", "orders"),
                "information_schema.columns", "ordinal_position");
        assertContains(generator.getPrimaryKeysSQL("test_db", "orders"), "PRIMARY", "ordinal_position");
        assertContains(generator.getForeignKeysSQL("test_db", "orders"), "referenced_table_name");
        assertContains(generator.getIndexesSQL("test_db", "orders"), "information_schema.statistics");
        assertContains(generator.getDatabaseMetadataSQL(), "VERSION()", "DATABASE()");
        assertContains(generator.getSchemasSQL(), "information_schema.schemata", "METRICS_SCHEMA");
        assertContains(generator.getTablesSQL("test_db"), "BASE TABLE", "test_db");
        assertContains(generator.getViewsSQL("test_db"), "information_schema.views", "test_db");
        assertContains(generator.getHealthCheckSQL(), "SELECT 1", "VERSION()");
        assertContains(generator.getAnalyzeTableSQL("test_db", "orders"), "ANALYZE TABLE", "`test_db`.`orders`");
        assertContains(generator.getOptimizeTableSQL("test_db", "orders"), "ANALYZE TABLE", "`test_db`.`orders`");
    }

    @Test
    void shouldEscapeMetadataIdentifiersUsedAsStringLiterals() {
        String sql = generator.getTableExistsSQL("tenant's_db", "orders'archive");

        assertContains(sql, "tenant''s_db", "orders''archive");
    }

    private void assertContains(String sql, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(sql.contains(fragment), () -> "SQL 缺少片段 " + fragment + ": " + sql);
        }
    }
}
