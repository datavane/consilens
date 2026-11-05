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
    void testSingleArgumentLimitClauseUsesOracleFetchSyntax() {
        // 复合键边界查询只传 limit；基类的 LIMIT n 在 Oracle 上会报 ORA-00933
        assertEquals("FETCH FIRST 1 ROWS ONLY", generator.getLimitClause(1));
    }

    @Test
    void testGetCountSQL() {
        String sql = generator.getCountSQL("SYSTEM", "users", null);
        assertTrue(sql.contains("COUNT(*)"));
        // 未加引号的标识符按 Oracle 语义折叠为大写
        assertTrue(sql.contains("\"SYSTEM\".\"USERS\""), sql);
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
        assertTrue(sql.contains("STANDARD_HASH"));
        // 聚合顺序以 (键, 行校验和) 双列确定：仅按键排序时同键多行的聚合顺序不确定
        assertTrue(sql.contains("LISTAGG(row_checksum, '|') WITHIN GROUP (ORDER BY pk_key, row_checksum)"), sql);
        assertFalse(sql.contains("DBMS_CRYPTO"));
    }

    @Test
    void testGetJoinDiffDetailSQLKeepsChangedColumnsWhenOnlySomeDiffer() {
        List<String> keyColumns = Arrays.asList("id");
        List<String> compareColumns = Arrays.asList("name", "amount", "status");

        String sql = generator.getJoinDiffDetailSQL("SYSTEM", "users", "t1",
                keyColumns, compareColumns, compareColumns, null,
                "SYSTEM", "users", "t2",
                keyColumns, compareColumns, compareColumns, null);

        // Oracle || treats NULL like '' while MySQL CONCAT_WS skips NULL elements, so
        // each changed column carries a leading ', ' separator and LTRIM strips the
        // first one. Unchanged columns therefore cannot null out or corrupt the whole
        // expression, matching MySQL output such as '["amount", "status"]'.
        assertTrue(sql.contains("'[' || ', ' || COALESCE(LTRIM("));
        assertTrue(sql.contains("THEN ', \"name\"'"));
        assertTrue(sql.contains("THEN ', \"amount\"'"));
        assertTrue(sql.contains("THEN ', \"status\"'"));
        assertFalse(sql.contains("ELSE NULL"));
        assertFalse(sql.contains("CONCAT_WS"));
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
