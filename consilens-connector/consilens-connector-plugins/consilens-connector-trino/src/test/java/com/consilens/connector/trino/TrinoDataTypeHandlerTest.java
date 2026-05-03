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
        assertEquals("COALESCE(TRIM(CAST(\"amount\" AS VARCHAR)), '0')", result);
    }

    @Test
    void testNormalizeColumn_Date() {
        String result = handler.normalizeColumn("created_at", DataType.DATE);
        assertTrue(result.contains("FORMAT_DATETIME"));
        assertTrue(result.contains("yyyy-MM-dd"));
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
