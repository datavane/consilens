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
        assertEquals("COALESCE(TRIM(TO_CHAR(\"amount\")), '0')", result);
    }

    @Test
    void testNormalizeColumn_Date() {
        String result = handler.normalizeColumn("created_at", DataType.DATE);
        assertTrue(result.contains("TO_CHAR"));
        assertTrue(result.contains("YYYY-MM-DD"));
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
}
