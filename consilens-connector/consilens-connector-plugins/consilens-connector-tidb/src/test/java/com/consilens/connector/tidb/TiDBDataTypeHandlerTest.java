package com.consilens.connector.tidb;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiDBDataTypeHandlerTest {

    private TiDBDataTypeHandler handler;
    private TiDBCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new TiDBCapabilityProvider();
        handler = new TiDBDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        assertEquals("COALESCE(TRIM(CAST(`amount` AS CHAR)), '0')", result);
    }

    @Test
    void shouldConvertUnsignedIntegerDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("int unsigned");

        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getType());
        assertEquals(32, descriptor.getBitWidth());
        assertTrue(descriptor.isUnsigned());
        assertEquals("int unsigned", handler.convertToOriginType(descriptor));
    }
}
