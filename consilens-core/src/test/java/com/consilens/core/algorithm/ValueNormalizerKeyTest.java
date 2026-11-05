package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ValueNormalizerKeyTest {

    @Test
    void decimalKeysKeepFullPrecision() {
        assertNotEquals(
                ValueNormalizer.normalizeKey(new BigDecimal("1.00001"), DataType.DECIMAL),
                ValueNormalizer.normalizeKey(new BigDecimal("1.00002"), DataType.DECIMAL));
    }

    @Test
    void decimalKeysIgnoreScaleAndTrailingZeros() {
        assertEquals(
                ValueNormalizer.normalizeKey(new BigDecimal("25.0000"), DataType.DECIMAL),
                ValueNormalizer.normalizeKey(new BigDecimal("25"), DataType.DECIMAL));
        assertEquals(
                ValueNormalizer.normalizeKey("25.0000", DataType.DECIMAL),
                ValueNormalizer.normalizeKey(25L, DataType.DECIMAL));
    }

    @Test
    void decimalKeysDoNotLosePrecisionThroughDouble() {
        BigDecimal huge = new BigDecimal("12345678901234567890.1234");
        assertEquals("12345678901234567890.1234",
                ValueNormalizer.normalizeKey(huge, DataType.DECIMAL));
        assertNotEquals(
                ValueNormalizer.normalizeKey(huge, DataType.DECIMAL),
                ValueNormalizer.normalizeKey(new BigDecimal("12345678901234567890.1235"), DataType.DECIMAL));
    }

    @Test
    void zeroScalesNormalizeToOneForm() {
        assertEquals("0", ValueNormalizer.normalizeKey(new BigDecimal("0.0000"), DataType.DECIMAL));
        assertEquals("0", ValueNormalizer.normalizeKey(new BigDecimal("-0.00"), DataType.DECIMAL));
    }

    @Test
    void decimalValuesMatchSqlFourDecimalContract() {
        assertEquals("25.0000", ValueNormalizer.normalizeValue(new BigDecimal("25"), DataType.DECIMAL));
        assertEquals("25.5000", ValueNormalizer.normalizeValue(25.5d, DataType.DECIMAL));
        assertEquals("0.0000", ValueNormalizer.normalizeValue(new BigDecimal("-0.00001"), DataType.DECIMAL));
        assertEquals("2.6750", ValueNormalizer.normalizeValue(new BigDecimal("2.675"), DataType.DECIMAL));
    }

    @Test
    void decimalValuesDoNotLosePrecisionThroughDouble() {
        BigDecimal huge = new BigDecimal("12345678901234567890.1234");
        assertEquals("12345678901234567890.1234",
                ValueNormalizer.normalizeValue(huge, DataType.DECIMAL));
        assertNotEquals(
                ValueNormalizer.normalizeValue(huge, DataType.DECIMAL),
                ValueNormalizer.normalizeValue(new BigDecimal("12345678901234567890.1235"), DataType.DECIMAL));
    }

    @Test
    void integersBeyondLongRangeDoNotCollide() {
        assertEquals("18446744073709551615",
                ValueNormalizer.normalizeKey("18446744073709551615", DataType.BIGINT));
        assertNotEquals(
                ValueNormalizer.normalizeKey("18446744073709551615", DataType.BIGINT),
                ValueNormalizer.normalizeKey("18446744073709551614", DataType.BIGINT));
        assertEquals("21", ValueNormalizer.normalizeKey(21.9d, DataType.INTEGER));
    }

    @Test
    void nonDecimalKeysKeepExistingSemantics() {
        assertEquals("21", ValueNormalizer.normalizeKey(" 21 ", DataType.INTEGER));
        assertEquals("abc", ValueNormalizer.normalizeKey("  abc  ", DataType.VARCHAR));
        assertEquals("", ValueNormalizer.normalizeKey(null, DataType.VARCHAR));
    }
}
