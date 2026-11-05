package com.consilens.core.segmentation;

import lombok.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable vector representing multi-dimensional key values.
 * Used for intelligent table segmentation and checkpoint selection.
 */
@Value
public class KeyVector {

    List<Comparable> values;

    public KeyVector(Comparable... values) {
        this.values = Arrays.asList(values);
    }

    public KeyVector(List<Comparable> values) {
        this.values = new ArrayList<>(values);
    }

    /**
     * Get value at specified dimension.
     */
    public Comparable getValue(int index) {
        return values.get(index);
    }

    /**
     * Get number of dimensions.
     */
    public int getDimensions() {
        return values.size();
    }

    /**
     * Check if this vector is less than another vector in lexicographic order.
     */
    public boolean isLessThan(KeyVector other) {
        return compareTo(other) < 0;
    }

    /** 与 compareValues 同一口径的字典序比较；数值类型不同但数值相等时返回 0。 */
    private int compareTo(KeyVector other) {
        if (values.size() != other.values.size()) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        for (int i = 0; i < values.size(); i++) {
            int cmp = compareValues(values.get(i), other.values.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * 边界值来自不同来源（JDBC 元数据、MIN/MAX 查询、用户输入），同一数值列可能是
     * BigDecimal / Double / Long。直接 compareTo 会抛 ClassCastException
     * （如 BigDecimal.compareTo(Double)），这里统一按数值比较。
     * NULL 视为最小（与 "NULL 排在最前" 的分段口径一致）。
     */
    private static int compareValues(Comparable left, Comparable right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number && right instanceof Number) {
            java.math.BigDecimal l = toBigDecimal((Number) left);
            java.math.BigDecimal r = toBigDecimal((Number) right);
            if (l != null && r != null) {
                return l.compareTo(r);
            }
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        return left.compareTo(right);
    }

    /** 非有限值（NaN/Infinity）返回 null，由调用方退回 double 比较。 */
    private static java.math.BigDecimal toBigDecimal(Number number) {
        if (number instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) number;
        }
        if (number instanceof java.math.BigInteger) {
            return new java.math.BigDecimal((java.math.BigInteger) number);
        }
        try {
            if (number instanceof Double || number instanceof Float) {
                return java.math.BigDecimal.valueOf(number.doubleValue());
            }
            return java.math.BigDecimal.valueOf(number.longValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Check if this vector is less than or equal to another vector.
     */
    public boolean isLessThanOrEqual(KeyVector other) {
        return compareTo(other) <= 0;
    }

    /**
     * Check if this vector is greater than another vector.
     */
    public boolean isGreaterThan(KeyVector other) {
        return compareTo(other) > 0;
    }

    /**
     * Check if this vector is greater than or equal to another vector.
     */
    public boolean isGreaterThanOrEqual(KeyVector other) {
        return compareTo(other) >= 0;
    }

    /**
     * Convert to string representation.
     */
    @Override
    public String toString() {
        return values.toString();
    }

    /**
     * Create a key vector from a raw bound value (a scalar or a list of scalars).
     */
    public static KeyVector fromKeyObject(Object keyObject) {
        if (keyObject == null) {
            return null;
        }
        if (keyObject instanceof List) {
            List<?> keyList = (List<?>) keyObject;
            List<Comparable> comparableValues = new ArrayList<>();
            for (Object value : keyList) {
                if (value instanceof Comparable) {
                    comparableValues.add((Comparable<?>) value);
                } else {
                    throw new IllegalArgumentException("Key values must be comparable: " + value);
                }
            }
            return new KeyVector(comparableValues);
        } else if (keyObject instanceof Comparable) {
            return new KeyVector((Comparable<?>) keyObject);
        } else {
            throw new IllegalArgumentException("Unsupported key object type: " + keyObject.getClass());
        }
    }

    /**
     * Convert to list.
     */
    public List<Comparable> toList() {
        return new ArrayList<>(values);
    }

    /**
     * Get hash code for this vector. Must agree with {@link #equals(Object)}:
     * numerically equal values of different Number types hash the same.
     */
    @Override
    public int hashCode() {
        int hash = 1;
        for (Comparable value : values) {
            hash = 31 * hash + canonicalHash(value);
        }
        return hash;
    }

    private static int canonicalHash(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            java.math.BigDecimal decimal = toBigDecimal((Number) value);
            if (decimal != null) {
                return decimal.stripTrailingZeros().toPlainString().hashCode();
            }
            return Double.valueOf(((Number) value).doubleValue()).hashCode();
        }
        return value.hashCode();
    }

    /**
     * Check equality with another vector using the same numeric semantics as ordering.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        KeyVector other = (KeyVector) obj;
        // equals 不能对维度不同的对象抛异常（同 compareTo 的严格校验区分开）
        if (values.size() != other.values.size()) return false;
        return compareTo(other) == 0;
    }
}