package com.consilens.connector.tidb;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TiDBSqlQueryGenerator.
 */
class TiDBSqlQueryGeneratorTest {

    private TiDBSqlQueryGenerator generator;
    private TiDBCapabilityProvider capabilityProvider;
    private TiDBDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new TiDBCapabilityProvider();
        dataTypeHandler = new TiDBDataTypeHandler(capabilityProvider);
        generator = new TiDBSqlQueryGenerator(capabilityProvider, dataTypeHandler);
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
}
