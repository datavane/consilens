package com.consilens.connector.starrocks;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DOUBLE 必须与 FLOAT 同口径（6 位小数），不能落到 DECIMAL 的 4 位归一化。 */
public class StarRocksDoubleDispatchTest {

    @Test
    public void doubleUsesFloatPrecision() {
        StarRocksDataTypeHandler handler = new StarRocksDataTypeHandler(new StarRocksCapabilityProvider(), null);
        String doubleExpr = handler.normalizeColumn("c_double", DataType.DOUBLE);
        String floatExpr = handler.normalizeColumn("c_float", DataType.FLOAT);

        assertFalse(doubleExpr.contains("DECIMAL(38,4)"), "DOUBLE 不应走 4 位小数归一化: " + doubleExpr);
        assertTrue(doubleExpr.contains("38,6"), "DOUBLE 应与 FLOAT 同为 6 位: " + doubleExpr);
        assertTrue(floatExpr.contains("38,6"), "FLOAT 基线: " + floatExpr);
    }
}
