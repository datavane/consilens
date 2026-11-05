package com.consilens.connector.doris;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DorisDataTypeHandler.
 */
class DorisDataTypeHandlerTest {

    private DorisDataTypeHandler handler;
    private DorisCapabilityProvider capabilityProvider;

    @BeforeEach
    void setUp() {
        capabilityProvider = new DorisCapabilityProvider();
        handler = new DorisDataTypeHandler(capabilityProvider);
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
    void testNormalizeFloatGuardsExtremeValuesBeforeDecimalCast() {
        String result = handler.normalizeFloat("`c_double`");
        // Doris 会对外层 CASE 的两支做类型统一；1e300 进入 DECIMAL(38,6) 会直接溢出。
        // 固定值分支必须先把极端值折成 NULL，科学计数分支才负责渲染它们。
        assertTrue(result.contains("CASE WHEN (`c_double` IS NOT NULL AND (ABS(`c_double`) > 1e10"),
                result);
        assertTrue(result.contains("THEN NULL ELSE `c_double` END"), result);
        assertTrue(result.contains("AS DECIMAL(38, 6)"), result);
    }

    @Test
    void testNormalizeDecimalCarriesRoundingIntoIntegerPart() {
        String result = handler.normalizeColumn("amount", DataType.DECIMAL);
        // 整段断言一次钉死：ROUND 直接作用在 DECIMAL 列上（不进 DOUBLE），
        // 再转成固定 scale 的 DECIMAL 保留尾零；NULL 哨兵为空串（不再是 '0.0000'）
        assertEquals("COALESCE(TRIM(CAST(CAST(ROUND(`amount`, 4) AS DECIMAL(38, 4)) AS CHAR)),'')",
                result);
        assertFalse(result.contains("Math.pow"));
        assertFalse(result.contains("FLOOR(ABS"));
        assertFalse(result.contains("AS DOUBLE"));
    }

    @Test
    void testNormalizeDecimalCapsPrecisionToDecimalLimit() {
        Map<String, Object> config = new HashMap<>();
        config.put("decimal", new DorisDecimalRule(38));
        DorisDataTypeHandler configured = new DorisDataTypeHandler(capabilityProvider, config);

        String result = configured.normalizeColumn("amount", DataType.DECIMAL);
        // precision > 30 must be capped to keep DECIMAL(38, scale) valid instead of
        // overflowing (long) Math.pow(10, precision).
        assertTrue(result.contains("AS DECIMAL(38, 30)"));
    }

    /** Minimal normalization rule with a getPrecision() method for test configs. */
    public static class DorisDecimalRule {
        private final int precision;

        public DorisDecimalRule(int precision) {
            this.precision = precision;
        }

        public int getPrecision() {
            return precision;
        }
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
    void shouldConvertArrayDescriptor() {
        TypeDescriptor descriptor = handler.convertToTypeDescriptor("ARRAY<INT>");

        assertEquals(com.consilens.common.enums.DataType.ARRAY_TYPE, descriptor.getType());
        assertEquals(com.consilens.common.enums.DataType.INTEGER_TYPE, descriptor.getElementType().getType());
        assertEquals("ARRAY<INT>", handler.convertToOriginType(descriptor));
    }
}
