package com.consilens.connector.postgresql;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSQLSqlQueryGenerator.
 */
class PostgreSQLSqlQueryGeneratorTest {

    private PostgreSQLSqlQueryGenerator generator;
    private PostgreSQLCapabilityProvider capabilityProvider;
    private PostgreSQLDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new PostgreSQLCapabilityProvider();
        dataTypeHandler = new PostgreSQLDataTypeHandler(capabilityProvider);
        generator = new PostgreSQLSqlQueryGenerator(capabilityProvider, dataTypeHandler);
    }

    @Test
    void testGetLimitClauseWithoutOffset() {
        String result = generator.getLimitClause(0, 10);
        assertEquals("LIMIT 10", result);
    }

    @Test
    void testGetLimitClauseWithOffset() {
        String result = generator.getLimitClause(5, 10);
        assertEquals("LIMIT 10 OFFSET 5", result);
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("public", "users", null);
        assertTrue(sql.contains("COUNT(*)"));
        assertTrue(sql.contains("\"public\".\"users\""));
    }

    @Test
    void testGetCountSQLWithWhere() {
        String sql = generator.getCountSQL("public", "users", "status = 'active'");
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.contains("status = 'active'"));
    }

    @Test
    void testGetChecksumSQL() {
        List<String> columns = Arrays.asList("id", "name");
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("created_at", DataType.DATETIME);

        String sql = generator.getChecksumSQL("public", "users", columns, Arrays.asList("id"), types, null);
        assertTrue(sql.contains("MD5"));
        assertTrue(sql.contains("STRING_AGG"));
    }

    @Test
    void testGetFullOuterJoinSQL() {
        List<String> joinColumns = Arrays.asList("id");
        String sql = generator.getFullOuterJoinSQL("table1", "table2", joinColumns, null);

        assertTrue(sql.contains("FULL OUTER JOIN"));
        assertFalse(sql.contains("UNION")); // PostgreSQL uses native FULL OUTER JOIN
    }

    @Test
    void testGetBatchInsertSQL() {
        List<String> columns = Arrays.asList("id", "name", "email");
        String sql = generator.getBatchInsertSQL("users", columns, 3);

        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("VALUES"));
        // Count question marks - should have 3 rows * 3 columns = 9
        long questionMarks = sql.chars().filter(ch -> ch == '?').count();
        assertEquals(9, questionMarks);
    }

    @Test
    void testGetBatchInsertSQLInvalidBatchSize() {
        List<String> columns = Arrays.asList("id", "name");
        assertThrows(IllegalArgumentException.class, () -> {
            generator.getBatchInsertSQL("users", columns, 0);
        });
    }

    @Test
    void testGetRowHashSQL() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);
        types.put("created_at", DataType.TIMESTAMP);

        String sql = generator.getRowHashSQL("public", "users",
                Arrays.asList("id"),
                Arrays.asList("id", "name", "amount", "created_at"),
                types,
                null);

        // Verify SELECT clause includes primary key
        assertTrue(sql.contains("SELECT \"id\""), "Should select primary key column");

        // Verify MD5 and CONCAT_WS with canonical separator
        assertTrue(sql.contains("MD5(CONCAT_WS('|', "), "Should use MD5(CONCAT_WS('|', ...))");

        // Verify row_hash alias
        assertTrue(sql.contains("AS row_hash"), "Should alias MD5 result as row_hash");

        // Verify FROM clause
        assertTrue(sql.contains("FROM \"public\".\"users\""), "Should include schema and table");

        // Verify normalization is delegated to dataTypeHandler
        assertTrue(sql.contains("COALESCE("), "Should use COALESCE for NULL handling");
        assertTrue(sql.contains("TRIM(CAST(\"id\" AS VARCHAR))"), "Should use normalized integer expression");

        // Verify no ORDER BY or STRING_AGG
        assertFalse(sql.contains("ORDER BY"), "Should avoid ORDER BY for sequential scan performance");
        assertFalse(sql.contains("STRING_AGG"), "Should not contain STRING_AGG");
    }

    @Test
    void testGetFingerprintSQLWithWhereClause() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getRowHashSQL("public", "users",
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

        String sql = generator.getRowHashSQL("public", "users",
                Arrays.asList("tenant_id", "user_id"),
                Arrays.asList("tenant_id", "user_id", "name"),
                types,
                null);

        // Verify both primary key columns are selected
        assertTrue(sql.contains("SELECT \"tenant_id\", \"user_id\""),
                "Should select both primary key columns");
    }

    @Test
    void testGetFingerprintSQLWithoutSchema() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getRowHashSQL(null, "users",
                Arrays.asList("id"),
                Arrays.asList("id", "name"),
                types,
                null);

        // Verify no schema prefix
        assertFalse(sql.contains("\".\""), "Should not include schema prefix");
        assertTrue(sql.contains("FROM \"users\""), "Should only include table name");
    }

    @Test
    void testGetFingerprintSQLWithVariousDataTypes() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);
        types.put("is_active", DataType.BOOLEAN);
        types.put("created_at", DataType.TIMESTAMP);
        types.put("data", DataType.JSONB);
        types.put("content", DataType.BLOB);

        String sql = generator.getRowHashSQL("public", "users",
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
        types.put("created_at", DataType.TIMESTAMP);
        types.put("is_active", DataType.BOOLEAN);

        String sql = generator.getRowHashSQL("public", "users",
                Arrays.asList("id"),
                Arrays.asList("id", "name", "amount", "created_at", "is_active"),
                types,
                "id BETWEEN 100 AND 200");

        System.out.println("\n=== Generated PostgreSQL Row-Hash SQL ===");
        System.out.println(sql);
        System.out.println("============================================\n");

        // Verify key requirements
        assertTrue(sql.contains("SELECT \"id\""));
        assertTrue(sql.contains("MD5(CONCAT_WS('|', "));
        assertTrue(sql.contains("AS row_hash"));
        assertFalse(sql.contains("ORDER BY"));
        assertFalse(sql.contains("STRING_AGG"));
    }
}
