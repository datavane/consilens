package com.consilens.connector.clickhouse;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClickHouseDataTypeHandler.
 */
class ClickHouseDataTypeHandlerTest {

    private ClickHouseDataTypeHandler handler;
    private ClickHouseCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new ClickHouseCapabilityProvider();
        handler = new ClickHouseDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        // 与本地路径同口径：字符串化 + TRIM + NULL → ''（不再返回原始列，
        // 否则本地比对时 NULL 原样带出，与其它方言不一致）
        assertEquals("COALESCE(trim(toString(`amount`)), '')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        assertEquals("COALESCE(formatDateTime(toTimeZone(`created_at`, 'UTC'), '%Y-%m-%d %H:%i:%S'), '')", result);
    }

    @Test
    void testNormalizeColumn_DecimalKeepsTrailingZeros() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        // 符号 + 整数部分 + 定长小数部分（lpad 补零）
        assertTrue(result.contains("abs(trunc("), result);
        assertTrue(result.contains("lpad("), result);
        // NULL 哨兵为空串（不再是 '0.0000'）
        assertTrue(result.contains("'')"), result);
    }

    @Test
    void testNormalizeColumn_Boolean() {
        String result = handler.normalizeColumn("is_active", DataType.BOOLEAN);
        assertTrue(result.contains("CASE WHEN"));
        assertTrue(result.contains("'1'"));
        assertTrue(result.contains("'0'"));
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
        String result = handler.getDataTypeMapping("jsonb", 0, 0, 0);
        assertEquals("JSONB", result);
    }

    @Test
    void shouldConvertNullableArrayDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("Nullable(Array(UInt32))");

        assertEquals(com.consilens.common.enums.DataType.ARRAY_TYPE, descriptor.getType());
        assertTrue(descriptor.isNullable());
        assertTrue(descriptor.getElementType().isUnsigned());
        assertEquals("Array(UInt32)", handler.convertToOriginType(
                TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE)
                        .elementType(TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                                .bitWidth(32)
                                .unsigned(true)
                                .build())
                        .build()));
    }

    @Test
    void shouldResolveWrappedTypesForRuntimeNormalization() {
        assertEquals(DataType.VARCHAR, handler.convertToDataType("Nullable(String)"));
        assertEquals(DataType.VARCHAR, handler.convertToDataType("LowCardinality(String)"));
    }
}
