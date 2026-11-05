package com.consilens.connector.trino;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrinoDataTypeHandlerTest {

    private TrinoDataTypeHandler handler;
    private TrinoCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new TrinoCapabilityProvider();
        handler = new TrinoDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumnNumeric() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        // NULL 哨兵统一为空串（与其它方言一致）
        assertEquals("COALESCE(TRIM(CAST(\"amount\" AS VARCHAR)), '')", result);
    }

    @Test
    void testNormalizeColumn_Date() {
        String result = handler.normalizeColumn("created_at", DataType.DATE);
        // DATE 与 checksum 路径同形（CAST + TRIM + NULL → ''）：返回原始列会把 NULL 原样带出，
        // 与其它方言的 NULL → '' 不一致（全 NULL 行误报）
        assertEquals("COALESCE(TRIM(CAST(\"created_at\" AS VARCHAR)), '')", result);
    }

    @Test
    void testNormalizeColumnTimestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        // 朴素 timestamp 按会话时区（JVM 时区）解释并换算到 UTC（与 mysql/pg 口径一致）
        assertEquals("COALESCE(FORMAT_DATETIME(AT_TIMEZONE(\"created_at\", 'UTC'), "
                + "'yyyy-MM-dd HH:mm:ss'), '')", result);
    }

    @Test
    void testFormatDateForChecksum() {
        String result = handler.formatDateForChecksum("\"created_at\"");
        assertTrue(result.contains("CAST(\"created_at\" AS VARCHAR)"));
        assertTrue(result.contains("COALESCE"));
    }

    @Test
    void testNormalizeDecimalKeepsTrailingZeros() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        assertTrue(result.contains("FORMAT('%."));
        assertTrue(result.contains("4f'"));
        // NULL 哨兵为空串（不再是 '0.0000'）
        assertTrue(result.contains("THEN ''"), result);
    }

    @Test
    void testNormalizeFloatKeepsTrailingZeros() {
        String result = handler.normalizeColumn("score", DataType.FLOAT);
        assertTrue(result.contains("FORMAT('%."));
        assertTrue(result.contains("CAST(\"score\" AS DOUBLE)"));
    }

    @Test
    void testGetDataTypeMappingVarchar() {
        String result = handler.getDataTypeMapping("varchar", 255, 0, 0);
        assertEquals("VARCHAR(255)", result);
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        String result = handler.getDataTypeMapping("decimal", 0, 10, 2);
        assertEquals("DECIMAL(10,2)", result);
    }

    @Test
    void testGetDataTypeMappingInteger() {
        String result = handler.getDataTypeMapping("integer", 0, 0, 0);
        assertEquals("INTEGER", result);
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        String result = handler.getDataTypeMapping("boolean", 0, 0, 0);
        assertEquals("BOOLEAN", result);
    }

    @Test
    void testGetDataTypeMappingJSON() {
        String result = handler.getDataTypeMapping("json", 0, 0, 0);
        assertEquals("JSON", result);
    }

    @Test
    void shouldConvertMapDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("MAP(VARCHAR, BIGINT)");

        assertEquals(com.consilens.common.enums.DataType.MAP_TYPE, descriptor.getType());
        assertEquals(com.consilens.common.enums.DataType.STRING_TYPE, descriptor.getKeyType().getType());
        assertEquals("MAP(VARCHAR, BIGINT)", handler.convertToOriginType(descriptor));
    }
}
