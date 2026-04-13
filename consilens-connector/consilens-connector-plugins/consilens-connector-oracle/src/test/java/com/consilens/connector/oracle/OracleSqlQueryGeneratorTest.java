package com.consilens.connector.oracle;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OracleSqlQueryGeneratorTest {

    private OracleSqlQueryGenerator generator;
    private OracleCapabilityProvider capabilityProvider;
    private OracleDataTypeHandler dataTypeHandler;

    @BeforeEach
    void setUp() {
        capabilityProvider = new OracleCapabilityProvider();
        dataTypeHandler = new OracleDataTypeHandler(capabilityProvider);
        generator = new OracleSqlQueryGenerator(capabilityProvider, dataTypeHandler);
    }

    @Test
    void testGetLimitClauseWithoutOffset() {
        String result = generator.getLimitClause(0, 10);
        assertEquals("FETCH FIRST 10 ROWS ONLY", result);
    }

    @Test
    void testGetLimitClauseWithOffset() {
        String result = generator.getLimitClause(5, 10);
        assertEquals("OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY", result);
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("SYSTEM", "users", null);
        assertTrue(sql.contains("COUNT(*)"));
        assertTrue(sql.contains("\"SYSTEM\".\"users\""));
    }

    @Test
    void testGetCountSQLWithWhere() {
        String sql = generator.getCountSQL("SYSTEM", "users", "status = 'active'");
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

        String sql = generator.getChecksumSQL("SYSTEM", "users", columns, Arrays.asList("id"), types, null);
        assertTrue(sql.contains("DBMS_CRYPTO"));
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
