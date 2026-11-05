package com.consilens.connector.oracle;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OracleDataTypeHandlerTest {

    private OracleDataTypeHandler handler;
    private OracleCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new OracleCapabilityProvider();
        handler = new OracleDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumnNumeric() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        // Oracle 未加引号的标识符折叠为大写；NULL 哨兵统一为空串
        assertEquals("COALESCE(TRIM(TO_CHAR(\"AMOUNT\")), '')", result);
    }

    @Test
    void testNormalizeColumn_Date() {
        String result = handler.normalizeColumn("created_at", DataType.DATE);
        assertTrue(result.contains("TO_CHAR"));
        assertTrue(result.contains("YYYY-MM-DD"));
    }

    @Test
    void testConvertDateTypeForCrossDatabaseComparison() {
        // Oracle DATE 按 DATE 语义映射：其它方言的 DATE 列只渲染日期部分，
        // 映射成 TIMESTAMP 会渲染出永不匹配的完整 UTC 时间
        assertEquals(DataType.DATE, handler.convertToDataType("DATE"));
        assertEquals(DataType.TIMESTAMP, handler.convertToDataType("TIMESTAMP"));
    }

    @Test
    void testOracleDateKeepsTimeWhenComparisonModeIsDatetime() {
        com.consilens.conncetor.base.jdbc.JdbcDatasetHandle.JdbcTypeNormalizationRule rule =
                new com.consilens.conncetor.base.jdbc.JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setComparisonMode("DATETIME");
        OracleDataTypeHandler datetimeHandler = new OracleDataTypeHandler(
                capabilityProvider, java.util.Map.of("date", rule));

        // 映射为 TIMESTAMP，Java 侧才会按 yyyy-MM-dd HH:mm:ss 归一化（与 SQL 侧同形）
        assertEquals(DataType.TIMESTAMP, datetimeHandler.convertToDataType("DATE"));
        String sql = datetimeHandler.normalizeColumn("created_at", DataType.DATE);
        assertTrue(sql.contains("HH24:MI:SS"), sql);
        assertTrue(sql.contains("YYYY-MM-DD"), sql);
    }

    @Test
    void testOracleDateStaysDateOnlyByDefault() {
        OracleDataTypeHandler defaultHandler = new OracleDataTypeHandler(capabilityProvider);
        String sql = defaultHandler.normalizeColumn("created_at", DataType.DATE);
        assertFalse(sql.contains("HH24:MI:SS"), sql);
    }

    @Test
    void testGetDataTypeMappingVarchar() {
        String result = handler.getDataTypeMapping("varchar", 255, 0, 0);
        assertEquals("VARCHAR2(255)", result);
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        String result = handler.getDataTypeMapping("decimal", 0, 10, 2);
        assertEquals("NUMBER(10,2)", result);
    }

    @Test
    void testGetDataTypeMappingInteger() {
        String result = handler.getDataTypeMapping("integer", 0, 0, 0);
        assertEquals("NUMBER(38)", result);
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        String result = handler.getDataTypeMapping("boolean", 0, 0, 0);
        assertEquals("NUMBER(1)", result);
    }

    @Test
    void testGetDataTypeMappingJSON() {
        String result = handler.getDataTypeMapping("json", 0, 0, 0);
        assertEquals("CLOB", result);
    }

    @Test
    void shouldConvertNumberDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("NUMBER(10,0)");

        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getType());
        assertEquals(64, descriptor.getBitWidth());
        assertEquals("NUMBER(10,0)", handler.convertToOriginType(TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                .numericPrecision(10)
                .numericScale(0)
                .build()));
    }

    @Test
    void decimalMaskCoversTheFullNumberPrecision() {
        // 15 位整数掩码会把 10^15 以上的值渲染成一串 '#'，不同的值因此变成同一个字符串
        String sql = handler.normalizeColumn("amount", DataType.DECIMAL);

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("'FM(9+)0\\.(0+)'").matcher(sql);
        assertTrue(matcher.find(), sql);
        // Oracle NUMBER 最大 38 位精度：掩码的整数位必须覆盖到 38
        assertEquals(38, matcher.group(1).length() + 1, "integer digits in " + sql);
        assertEquals(4, matcher.group(2).length());
    }
}
