package com.consilens.connector.mysql;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.jdbc.JdbcDatasetHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MySQLDataTypeHandler.
 */
class MySQLDataTypeHandlerTest {

    private MySQLDataTypeHandler handler;
    private MySQLCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new MySQLCapabilityProvider();
        handler = new MySQLDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("age", DataType.INTEGER);
        assertEquals("COALESCE(TRIM(CAST(`age` AS CHAR)), '0')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimestampDateOnlyMode() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setComparisonMode("DATE_ONLY");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of("timestamp", rule));

        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);

        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, '+00:00'), '%Y-%m-%d'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimestampWithConfiguredNativeFormatAndTimezone() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("%Y/%m/%d %H:%i:%s");
        rule.setTimezone("Asia/Shanghai");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of("timestamp", rule));

        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);

        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, 'Asia/Shanghai'), '%Y/%m/%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimestampWithTimezoneUsesExactRuleBeforeFallback() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule fallbackRule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        fallbackRule.setFormat("%Y-%m-%d %H:%i:%s");
        fallbackRule.setTimezone("UTC");
        JdbcDatasetHandle.JdbcTypeNormalizationRule exactRule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        exactRule.setFormat("%Y/%m/%d %H:%i:%s");
        exactRule.setTimezone("Asia/Shanghai");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of(
                "timestamp", fallbackRule,
                "timestamp_with_timezone", exactRule));

        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP_WITH_TIMEZONE);

        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, 'Asia/Shanghai'), '%Y/%m/%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimestampWithTimezoneFallsBackToTimestampRule() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("%Y/%m/%d %H:%i:%s");
        rule.setTimezone("Asia/Shanghai");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of("timestamp", rule));

        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP_WITH_TIMEZONE);

        assertEquals(
                "COALESCE(DATE_FORMAT(CONVERT_TZ(`created_at`, @@session.time_zone, 'Asia/Shanghai'), '%Y/%m/%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_TimeWithTimezoneUsesExactRule() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("%H:%i");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of("time_with_timezone", rule));

        String result = handler.normalizeColumn("paid_at", DataType.TIME_WITH_TIME_ZONE);

        assertEquals("COALESCE(TIME_FORMAT(`paid_at`, '%H:%i'), '')", result);
    }

    @Test
    void testNormalizeColumn_RejectsNonNativeTimestampFormat() {
        JdbcDatasetHandle.JdbcTypeNormalizationRule rule = new JdbcDatasetHandle.JdbcTypeNormalizationRule();
        rule.setFormat("yyyy-MM-dd");
        handler = new MySQLDataTypeHandler(capabilityProvider, Map.of("timestamp", rule));

        assertThrows(ConnectorException.class, () -> handler.normalizeColumn("created_at", DataType.TIMESTAMP));
    }

    @Test
    void testNormalizeColumn_Boolean() {
        String result = handler.normalizeColumn("is_active", DataType.BOOLEAN);
        assertTrue(result.contains("CASE"));
        assertTrue(result.contains("'1'"));
        assertTrue(result.contains("'0'"));
    }

    @Test
    void testNormalizeColumn_Bit() {
        String result = handler.normalizeColumn("is_active", DataType.BIT);
        assertTrue(result.contains("CASE"));
        assertTrue(result.contains("'1'"));
        assertTrue(result.contains("'0'"));
    }

    @Test
    void testGetDataTypeMappingVarchar() {
        assertEquals("VARCHAR(100)", handler.getDataTypeMapping("varchar", 100, 0, 0));
        assertEquals("VARCHAR(255)", handler.getDataTypeMapping("varchar", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingDecimal() {
        assertEquals("DECIMAL(10,2)", handler.getDataTypeMapping("decimal", 0, 10, 2));
        assertEquals("DECIMAL(10)", handler.getDataTypeMapping("decimal", 0, 10, 0));
        assertEquals("DECIMAL", handler.getDataTypeMapping("decimal", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingInteger() {
        assertEquals("INT", handler.getDataTypeMapping("int", 0, 0, 0));
        assertEquals("BIGINT", handler.getDataTypeMapping("bigint", 0, 0, 0));
        assertEquals("TINYINT", handler.getDataTypeMapping("tinyint", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingBoolean() {
        assertEquals("TINYINT(1)", handler.getDataTypeMapping("boolean", 0, 0, 0));
        assertEquals("TINYINT(1)", handler.getDataTypeMapping("bool", 0, 0, 0));
    }

    @Test
    void testGetDataTypeMappingJSON() {
        assertEquals("JSON", handler.getDataTypeMapping("json", 0, 0, 0));
    }

    @Test
    void shouldConvertUnsignedIntegerDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("bigint unsigned");

        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getType());
        assertEquals(64, descriptor.getBitWidth());
        assertTrue(descriptor.isUnsigned());
        assertEquals("bigint unsigned", handler.convertToOriginType(descriptor));
    }
}
