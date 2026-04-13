package com.consilens.connector.clickhouse;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClickHouseSqlQueryGenerator.
 */
class ClickHouseSqlQueryGeneratorTest {

    private ClickHouseSqlQueryGenerator generator;
    private ClickHouseCapabilityProvider capabilityProvider;
    private ClickHouseDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new ClickHouseCapabilityProvider();
        dataTypeHandler = new ClickHouseDataTypeHandler(capabilityProvider);
        generator = new ClickHouseSqlQueryGenerator(capabilityProvider, dataTypeHandler);
    }

    @Test
    void testGetLimitClauseWithoutOffset() {
        String result = generator.getLimitClause(0, 10);
        assertEquals("LIMIT 10", result);
    }

    @Test
    void testGetLimitClauseWithOffset() {
        String result = generator.getLimitClause(5, 10);
        assertEquals("LIMIT 5, 10", result); // ClickHouse MySQL-style syntax
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("public", "users", null);
        assertTrue(sql.contains("COUNT(*)"));
        assertTrue(sql.contains("`public`.`users`")); // backtick quotes
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
        assertTrue(sql.contains("groupConcat"));
    }

    @Test
    void testGetFullOuterJoinSQL() {
        List<String> joinColumns = Arrays.asList("id");
        String sql = generator.getFullOuterJoinSQL("table1", "table2", joinColumns, null);

        assertTrue(sql.contains("FULL OUTER JOIN"));
        assertFalse(sql.contains("UNION")); // ClickHouse uses native FULL OUTER JOIN
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
}
