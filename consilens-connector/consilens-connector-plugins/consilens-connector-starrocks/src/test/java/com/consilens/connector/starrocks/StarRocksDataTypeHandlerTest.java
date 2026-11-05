package com.consilens.connector.starrocks;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StarRocksDataTypeHandler.
 */
class StarRocksDataTypeHandlerTest {

    private StarRocksDataTypeHandler handler;
    private StarRocksCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new StarRocksCapabilityProvider();
        handler = new StarRocksDataTypeHandler(capabilityProvider);
    }

    @Test
    void testNormalizeColumn_Int() {
        String result = handler.normalizeColumn("age", DataType.INTEGER);
        // NULL 哨兵统一为空串（与其它方言一致）
        assertEquals("COALESCE(TRIM(CAST(`age` AS CHAR)), '')", result);
    }

    @Test
    void testNormalizeColumn_Timestamp() {
        String result = handler.normalizeColumn("created_at", DataType.TIMESTAMP);
        // 以固定锚点求偏移，再整体平移：命名时区不依赖 mysql.time_zone 表
        assertEquals(
                "COALESCE(DATE_FORMAT(DATE_ADD(`created_at`, INTERVAL -TIMESTAMPDIFF(SECOND, "
                        + "CONVERT_TZ('2020-06-01 12:00:00', @@session.time_zone, '+00:00'), "
                        + "'2020-06-01 12:00:00') SECOND), '%Y-%m-%d %H:%i:%s'), '')",
                result);
    }

    @Test
    void testNormalizeColumn_Boolean() {
        String result = handler.normalizeColumn("is_active", DataType.BOOLEAN);
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
    void shouldConvertStructDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("STRUCT<id:BIGINT, name:STRING>");

        assertEquals(com.consilens.common.enums.DataType.STRUCT_TYPE, descriptor.getType());
        assertEquals(2, descriptor.getFields().size());
        assertEquals("STRUCT<id:BIGINT, name:STRING>", handler.convertToOriginType(descriptor));
    }
}
