package com.consilens.connector.trino;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrinoSqlQueryGeneratorTest {

    private TrinoSqlQueryGenerator generator;
    private TrinoCapabilityProvider capabilityProvider;
    private TrinoDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new TrinoCapabilityProvider();
        dataTypeHandler = new TrinoDataTypeHandler(capabilityProvider);
        generator = new TrinoSqlQueryGenerator(capabilityProvider, dataTypeHandler);
    }

    @Test
    void testGetLimitClauseWithoutOffset() {
        String result = generator.getLimitClause(0, 10);
        assertEquals("LIMIT 10", result);
    }

    @Test
    void testGetLimitClauseWithOffset() {
        String result = generator.getLimitClause(5, 10);
        assertEquals("OFFSET 5 LIMIT 10", result);
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("default", "users", null);
        assertTrue(sql.contains("COUNT(*)"));
        assertTrue(sql.contains("\"default\".\"users\""));
    }

    @Test
    void testGetCountSQLWithWhere() {
        String sql = generator.getCountSQL("default", "users", "status = 'active'");
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

        String sql = generator.getChecksumSQL("default", "users", columns, Arrays.asList("id"), types, null);
        assertTrue(sql.toLowerCase().contains("md5"));
    }

    @Test
    void testGetFullOuterJoinSQL() {
        List<String> joinColumns = Arrays.asList("id");
        String sql = generator.getFullOuterJoinSQL("table1", "table2", joinColumns, null);

        assertTrue(sql.contains("FULL OUTER JOIN"));
    }

    @Test
    void testGetBatchInsertSQL() {
        List<String> columns = Arrays.asList("id", "name", "email");
        String sql = generator.getBatchInsertSQL("users", columns, 3);

        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("VALUES"));
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
