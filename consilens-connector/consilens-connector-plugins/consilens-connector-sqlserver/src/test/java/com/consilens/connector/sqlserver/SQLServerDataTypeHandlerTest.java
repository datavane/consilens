package com.consilens.connector.sqlserver;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.jdbc.JdbcDatasetHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SQLServerDataTypeHandler.
 */
class SQLServerDataTypeHandlerTest {

    private SQLServerDataTypeHandler handler;
    private SQLServerCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new SQLServerCapabilityProvider();
        handler = new SQLServerDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("amount", DataType.INTEGER);
        assertEquals("COALESCE(LTRIM(RTRIM(CAST([amount] AS VARCHAR(MAX)))), '0')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.DATETIME);
        assertEquals("COALESCE(FORMAT([created_at], 'yyyy-MM-dd HH:mm:ss'), '')", result);
    }

    @Test
    void testNormalizeColumn_DateTimeWithConfiguredNativeFormatAndTimezone() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("yyyy/MM/dd HH:mm:ss");
        rule.setTimezone("+08:00");
        handler = new SQLServerDataTypeHandler(capabilityProvider, Map.of("datetime", rule));

        String result = handler.normalizeColumn("created_at", DataType.DATETIME);

        assertEquals("COALESCE(FORMAT(SWITCHOFFSET([created_at], '+08:00'), 'yyyy/MM/dd HH:mm:ss'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_RejectsNonNativeDateTimeFormat() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("YYYY-MM-DD");
        handler = new SQLServerDataTypeHandler(capabilityProvider, Map.of("datetime", rule));

        assertThrows(ConnectorException.class, () -> handler.normalizeColumn("created_at", DataType.DATETIME));
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
        assertEquals("NVARCHAR(255)", result);
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        String result = handler.getDataTypeMapping("decimal", 0, 10, 2);
        assertEquals("DECIMAL(10,2)", result);
    }

    @Test
    void testGetDataTypeMappingInteger() {
        String result = handler.getDataTypeMapping("integer", 0, 0, 0);
        assertEquals("INT", result);
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        String result = handler.getDataTypeMapping("boolean", 0, 0, 0);
        assertEquals("BIT", result);
    }

    @Test
    void testGetDataTypeMappingJSON() {
        String result = handler.getDataTypeMapping("json", 0, 0, 0);
        assertEquals("NVARCHAR(MAX)", result);
    }

    @Test
    void shouldConvertDateTimeOffsetDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("datetimeoffset(7)");

        assertEquals(com.consilens.common.enums.DataType.TIMESTAMP_TYPE, descriptor.getType());
        assertTrue(descriptor.isWithTimezone());
        assertEquals("datetimeoffset(7)", handler.convertToOriginType(descriptor));
    }
}
