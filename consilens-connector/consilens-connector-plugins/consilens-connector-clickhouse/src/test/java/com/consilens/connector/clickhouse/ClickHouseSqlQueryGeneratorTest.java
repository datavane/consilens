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
        assertTrue(sql.contains("arrayStringConcat"));
        assertTrue(sql.contains("groupArray"));
        // 排序键取 (键, 行校验和) 双列：仅按键排序时同键多行的聚合顺序不确定
        assertTrue(sql.contains("ORDER BY pk_key, row_checksum LIMIT 18446744073709551615"), sql);
        assertFalse(sql.contains("LIMIT 10000000"));
    }

    @Test
    void testGetChecksumSQLNormalizesNullIntegers() {
        List<String> columns = Arrays.asList("id", "amount");
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("amount", DataType.BIGINT);

        String sql = generator.getChecksumSQL("public", "orders", columns, Arrays.asList("id"), types, null);
        // NULL 整数在 concat() 内折叠为空串，与其它方言的 COALESCE(..., '') 归一化一致；
        // 否则行校验和会不一致
        assertTrue(sql.contains("COALESCE(trim(toString("));
        assertTrue(sql.contains("), '')"));
    }

    @Test
    void testGetRowHashSQLNormalizesNullIntegers() {
        List<String> columns = Arrays.asList("id", "amount");
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("amount", DataType.BIGINT);

        String sql = generator.getRowHashSQL("public", "orders", Arrays.asList("id"), columns, types, null);
        assertTrue(sql.contains("COALESCE(trim(toString("));
        assertTrue(sql.contains("), '')"));
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
    void keyMultiplicityFilterIsDisabledBecauseClickHouseRejectsCorrelatedSubqueries() {
        // 键多重性过滤器依赖关联子查询；ClickHouse 不支持，硬用会把重复键重复计入 presence
        // （真机 join-edge-dup 期望 (6,3,1,2) 实测 (11,6,3,2)），因此与 Doris/Trino 一致退化为
        // 整表本地多重集配对。
        assertFalse(generator.supportsKeyMultiplicityFilter());
    }

}
