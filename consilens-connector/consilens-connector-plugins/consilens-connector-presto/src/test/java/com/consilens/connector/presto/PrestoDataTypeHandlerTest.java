package com.consilens.connector.presto;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrestoDataTypeHandlerTest {

    private PrestoDataTypeHandler handler;
    private PrestoCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new PrestoCapabilityProvider();
        handler = new PrestoDataTypeHandler(capabilityProvider);
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
        assertTrue(result.contains("FORMAT_DATETIME"));
        assertTrue(result.contains("yyyy-MM-dd"));
    }

    @Test
    void testNormalizeDecimalKeepsTrailingZeros() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        assertTrue(result.contains("AS DECIMAL(38, 4)"));
        // NULL 哨兵为空串（不再是 '0.0000'）
        assertTrue(result.contains("'')"), result);
    }

    @Test
    void testNormalizeFloatKeepsTrailingZeros() {
        String result = handler.normalizeColumn("score", DataType.FLOAT);
        assertTrue(result.contains("AS DECIMAL(38, 6)"));
        assertTrue(result.contains("CAST(\"score\" AS DOUBLE)"));
    }

    @Test
    void testNormalizeDoubleUsesFloatFamilyRendering() {
        String result = handler.normalizeColumn("ratio", DataType.DOUBLE);
        // DOUBLE 与 FLOAT 同族：常规区间定点渲染 + 超区间（|v|>1e10 / 0<|v|<1e-10）科学计数，
        // 与本地路径 ValueNormalizer.normalizeFloatingPoint 同口径
        assertTrue(result.contains("CASE WHEN"), result);
        assertTrue(result.contains("AS DECIMAL(38, 6)"), result);
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
    void shouldConvertRowDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("ROW(id BIGINT, name VARCHAR)");

        assertEquals(com.consilens.common.enums.DataType.STRUCT_TYPE, descriptor.getType());
        assertEquals(2, descriptor.getFields().size());
        assertEquals("ROW(id BIGINT, name VARCHAR)", handler.convertToOriginType(descriptor));
    }
}
