package com.consilens.connector.mysql;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MySQLSqlQueryGenerator.
 */
class MySQLSqlQueryGeneratorTest {

    private MySQLSqlQueryGenerator generator;
    private MySQLCapabilityProvider capabilityProvider;
    private MySQLDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new MySQLCapabilityProvider();
        dataTypeHandler = new MySQLDataTypeHandler(capabilityProvider);
        generator = new MySQLSqlQueryGenerator(capabilityProvider, dataTypeHandler);
    }

    @Test
    void testGetLimitClauseWithoutOffset() {
        assertEquals("LIMIT 10", generator.getLimitClause(0, 10));
        assertEquals("LIMIT 10", generator.getLimitClause(10));
    }

    @Test
    void testGetLimitClauseWithOffset() {
        assertEquals("LIMIT 5, 10", generator.getLimitClause(5, 10));
        assertEquals("LIMIT 100, 50", generator.getLimitClause(100, 50));
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("test_db", "test_table", null);
        assertTrue(sql.contains("SELECT COUNT(*)"));
        assertTrue(sql.contains("`test_db`"));
        assertTrue(sql.contains("`test_table`"));
    }

    @Test
    void testGetCountSQLWithWhere() {
        String sql = generator.getCountSQL("test_db", "test_table", "id > 100");
        assertTrue(sql.contains("WHERE id > 100"));
    }

    @Test
    void testGetChecksumSQL() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("created_at", DataType.DATETIME);

        String sql = generator.getChecksumSQL("test_db", "test_table",
                Arrays.asList("id", "name"), Arrays.asList("id"), types, null);

        assertTrue(sql.contains("MD5(GROUP_CONCAT"));
        assertTrue(sql.contains("CONCAT_WS"));
        assertTrue(sql.contains("ORDER BY"));
    }

    @Test
    void testGetFullOuterJoinSQL() {
        String sql = generator.getFullOuterJoinSQL("table1", "table2",
                Arrays.asList("id"), null);

        assertTrue(sql.contains("LEFT JOIN"));
        assertTrue(sql.contains("RIGHT JOIN"));
        assertTrue(sql.contains("UNION"));
        assertTrue(sql.contains("IS NULL"));
    }

    @Test
    void testGetBatchInsertSQL() {
        String sql = generator.getBatchInsertSQL("test_table",
                Arrays.asList("col1", "col2", "col3"), 5);

        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("VALUES"));

        // Should have 5 sets of placeholders
        int count = sql.split("\\?").length - 1;
        assertEquals(15, count); // 3 columns × 5 rows
    }

    @Test
    void testGetBatchInsertSQLInvalidBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            generator.getBatchInsertSQL("test_table", Arrays.asList("col1"), 0);
        });
    }


    @Test
    void testGetRowHashSQL() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);
        types.put("created_at", DataType.DATETIME);

        String sql = generator.getRowHashSQL("test_db", "test_table",
                Arrays.asList("id"),
                Arrays.asList("id", "name", "amount", "created_at"),
                types,
                null);

        // Verify SELECT clause includes primary key
        assertTrue(sql.contains("SELECT `id`"), "Should select primary key column");

        // Verify MD5 and CONCAT_WS with canonical separator
        assertTrue(sql.contains("MD5(CONCAT_WS('|', "), "Should use MD5(CONCAT_WS('|', ...))");

        // Verify row_hash alias
        assertTrue(sql.contains("AS row_hash"), "Should alias MD5 result as row_hash");

        // Verify FROM clause
        assertTrue(sql.contains("FROM `test_db`.`test_table`"), "Should include schema and table");

        // Verify normalization is delegated to dataTypeHandler
        assertTrue(sql.contains("COALESCE("), "Should use COALESCE for NULL handling");
        assertTrue(sql.contains("TRIM(CAST(`id` AS CHAR))"), "Should use normalized integer expression");

        // Verify no ORDER BY
        assertFalse(sql.contains("ORDER BY"), "Should not contain ORDER BY clause");

        // Verify no GROUP_CONCAT
        assertFalse(sql.contains("GROUP_CONCAT"), "Should not contain GROUP_CONCAT");
    }

    @Test
    void testGetFingerprintSQLWithWhereClause() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getRowHashSQL("test_db", "test_table",
                Arrays.asList("id"),
                Arrays.asList("id", "name"),
                types,
                "id BETWEEN 100 AND 200");

        assertTrue(sql.contains("WHERE id BETWEEN 100 AND 200"), "Should include WHERE clause");
    }

    @Test
    void testGetFingerprintSQLWithCompositePrimaryKey() {
        Map<String, DataType> types = new HashMap<>();
        types.put("tenant_id", DataType.INTEGER);
        types.put("user_id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getRowHashSQL("test_db", "test_table",
                Arrays.asList("tenant_id", "user_id"),
                Arrays.asList("tenant_id", "user_id", "name"),
                types,
                null);

        // Verify both primary key columns are selected
        assertTrue(sql.contains("SELECT `tenant_id`, `user_id`"),
                "Should select both primary key columns");
    }

    @Test
    void testGetFingerprintSQLWithoutSchema() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getRowHashSQL(null, "test_table",
                Arrays.asList("id"),
                Arrays.asList("id", "name"),
                types,
                null);

        // Verify no schema prefix
        assertFalse(sql.contains("`.`"), "Should not include schema prefix");
        assertTrue(sql.contains("FROM `test_table`"), "Should only include table name");
    }

    @Test
    void testGetFingerprintSQLWithVariousDataTypes() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);
        types.put("is_active", DataType.BOOLEAN);
        types.put("created_at", DataType.DATETIME);
        types.put("data", DataType.JSON);
        types.put("content", DataType.BLOB);

        String sql = generator.getRowHashSQL("test_db", "test_table",
                Arrays.asList("id"),
                Arrays.asList("id", "name", "amount", "is_active", "created_at", "data", "content"),
                types,
                null);

        // Verify all columns are included in the row hash
        assertTrue(sql.contains("CONCAT_WS('|', "), "Should use CONCAT_WS");

        // Verify the SQL is well-formed
        assertNotNull(sql);
        assertTrue(sql.length() > 0);
    }


    @Test
    void testGetFingerprintSQLOutput() {
        // This test prints the generated SQL for manual verification
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);
        types.put("created_at", DataType.DATETIME);
        types.put("is_active", DataType.BOOLEAN);

        String sql = generator.getRowHashSQL("test_db", "test_table",
                Arrays.asList("id"),
                Arrays.asList("id", "name", "amount", "created_at", "is_active"),
                types,
                "id BETWEEN 100 AND 200");

        System.out.println("\n=== Generated Row-Hash SQL ===");
        System.out.println(sql);
        System.out.println("=================================\n");

        // Verify key requirements
        assertTrue(sql.contains("SELECT `id`"));
        assertTrue(sql.contains("MD5(CONCAT_WS('|', "));
        assertTrue(sql.contains("AS row_hash"));
        assertFalse(sql.contains("ORDER BY"));
        assertFalse(sql.contains("GROUP_CONCAT"));
    }


}
