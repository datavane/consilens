package com.consilens.connector.mysql;

import com.consilens.common.enums.ChecksumAlgorithm;
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
    void shouldOmitEmptyKeyPredicateInsteadOfGeneratingInvalidInClause() {
        String sql = generator.getSelectByKeysSQL("test_db", "test_table",
                List.of("`id`"), List.of("id"),
                List.<List<Object>>of(List.of()), null, List.of("id"));

        assertFalse(sql.contains("IN ()"), sql);
        assertFalse(sql.contains("WHERE"), sql);
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
    void testGetChecksumSQLFromSqlUsesSubquerySource() {
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);

        String sql = generator.getChecksumSQLFromSql(
                "SELECT id, name FROM orders",
                List.of("id"),
                List.of("id", "name"),
                types,
                "id >= 100",
                ChecksumAlgorithm.CONCAT);

        assertTrue(sql.contains("FROM (SELECT id, name FROM orders) consilens_sql_source"));
        assertTrue(sql.contains("WHERE id >= 100"));
        assertFalse(sql.contains("CREATE TEMPORARY"));
        assertFalse(sql.contains("`(SELECT"));
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

        // P1-2: 值内 '|' 必须双写，否则 ('a|b','c') 与 ('a','b|c') 会拼成同一串
        assertTrue(sql.contains("REPLACE("), "Should escape separator inside values");
        assertTrue(sql.contains(", '|', '||')"), "Separator must be doubled, not dropped");

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



    @Test
    void shouldEscapeQuotedColumnNamesInJoinDiffDetailSql() {
        Map<String, DataType> types = Map.of("id", DataType.VARCHAR,
                "odd'name", DataType.VARCHAR);
        String sql = generator.getJoinDiffDetailSQL("db", "t1", "t1",
                List.of("id"), List.of("odd'name"), List.of("odd'name"), null, types,
                "db", "t2", "t2",
                List.of("id"), List.of("odd'name"), List.of("odd'name"), null, types);

        assertTrue(sql.contains("AS `odd'name_1`"), sql);
        assertTrue(sql.contains("AS `odd'name_2`"), sql);
        assertFalse(sql.contains("AS odd'name_1"), sql);
        // 变更列 JSON 里的列名必须按 SQL 字符串字面量转义（MySQL 单引号双写）
        assertTrue(sql.contains("THEN '\"odd''name\"'"), sql);
    }

    @Test
    void testNullKeyPresenceUsesNormalizedEquality() {
        // t1 键为 NULL、t2 键为 ''：归一化 join 认为二者相等，存在性判断也必须跟随，
        // 否则会统计成 source_missing，并漏掉真实的值差异。
        Map<String, DataType> types = Map.of("id", DataType.VARCHAR);
        String sql = generator.getJoinDiffStatsSQL("db", "t1", "t1",
                List.of("id"), List.of("name"), null, types,
                "db", "t2", "t2",
                List.of("id"), List.of("name"), null, types);

        assertTrue(sql.contains("COALESCE"), "归一化表达式必须出现在存在性判断里");
        assertTrue(sql.contains("AND NOT (HEX(COALESCE"), "缺失判断必须排除归一化后相等的行");
        assertFalse(sql.contains("WHERE (t1.`id` IS NULL AND NOT (t2.`id` IS NULL))"),
                "不能再退回裸 IS NULL 的存在性判断");
    }
}
