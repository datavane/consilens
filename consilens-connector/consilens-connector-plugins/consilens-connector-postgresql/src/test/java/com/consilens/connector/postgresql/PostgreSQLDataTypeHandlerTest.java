package com.consilens.connector.postgresql;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.jdbc.JdbcDatasetHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgreSQLDataTypeHandler.
 */
class PostgreSQLDataTypeHandlerTest {

    private PostgreSQLDataTypeHandler handler;
    private PostgreSQLCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new PostgreSQLCapabilityProvider();
        handler = new PostgreSQLDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        assertEquals("COALESCE(TRIM(CAST(\"amount\" AS VARCHAR)), '0')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        assertEquals(
                "COALESCE(TO_CHAR(\"created_at\" AT TIME ZONE current_setting('TIMEZONE') AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimestampDateOnlyModeWithTimezone() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setComparisonMode("TRUNCATE_TO_DAY");
        rule.setTimezone("Asia/Shanghai");
        handler = new PostgreSQLDataTypeHandler(capabilityProvider, Map.of("timestamp", rule));

        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);

        assertEquals(
                "COALESCE(TO_CHAR(\"created_at\" AT TIME ZONE current_setting('TIMEZONE') AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD'), '')",
                result);
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
    void testConvertBpcharToChar() {
        assertEquals(DataType.CHAR, handler.convertToDataType("bpchar"));
    }

    @Test
    void shouldConvertArrayDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("int8[]");

        assertEquals(com.consilens.common.enums.DataType.ARRAY_TYPE, descriptor.getType());
        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getElementType().getType());
        assertEquals("BIGINT[]", handler.convertToOriginType(
                TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE)
                        .elementType(TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).bitWidth(64).build())
                        .build()));
    }
}
