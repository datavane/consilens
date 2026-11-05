package com.consilens.core.segmentation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键边界可能来自不同来源（JDBC 元数据 / MIN-MAX 查询 / 用户输入），
 * 同一数值列的运行时类型不一定是 BigDecimal。这里锁定跨数值类型的比较行为。
 */
class KeyVectorTest {

    @Test
    void comparesNumericTypesAcrossRepresentations() {
        assertTrue(new KeyVector(new BigDecimal("1.5")).isLessThan(new KeyVector(2.0d)));
        assertTrue(new KeyVector(1.5d).isLessThan(new KeyVector(new BigDecimal("2"))));
        assertFalse(new KeyVector(new BigDecimal("2.0")).isLessThan(new KeyVector(2L)));
        assertTrue(new KeyVector(1).isGreaterThanOrEqual(new KeyVector(new BigDecimal("1.0"))));
        assertTrue(new KeyVector(10L).isGreaterThan(new KeyVector(9.99d)));
    }

    @Test
    void treatsNumericallyEqualValuesAsEqualAcrossTypes() {
        KeyVector integer = new KeyVector(1);
        KeyVector decimal = new KeyVector(new BigDecimal("1.0"));

        assertTrue(integer.equals(decimal));
        assertEquals(integer.hashCode(), decimal.hashCode());
        assertFalse(integer.isLessThan(decimal));
        assertFalse(integer.isGreaterThan(decimal));
        assertTrue(integer.isLessThanOrEqual(decimal));
        assertTrue(integer.isGreaterThanOrEqual(decimal));
    }

    @Test
    void differentDimensionsAreNotEqual() {
        assertFalse(new KeyVector(1).equals(new KeyVector(1, 2)));
    }

    @Test
    void ordersNullsFirst() {
        assertTrue(new KeyVector((Comparable) null).isLessThan(new KeyVector(1)));
        assertFalse(new KeyVector(1).isLessThan(new KeyVector((Comparable) null)));
        assertFalse(new KeyVector((Comparable) null).isLessThan(new KeyVector((Comparable) null)));
    }
}
