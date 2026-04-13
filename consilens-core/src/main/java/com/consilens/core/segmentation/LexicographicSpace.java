package com.consilens.core.segmentation;

import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Lexicographic space implementation for intelligent table segmentation.
 * Supports multi-dimensional key space division with uniform distribution.
 */
@Slf4j
public class LexicographicSpace {

    public LexicographicSpace() {
    }

    /**
     * Generate uniformly distributed checkpoints between min and max values.
     */
    public List<KeyVector> range(KeyVector min, KeyVector max, int count) {
        if (min == null || max == null || count <= 0) {
            return List.of(min, max);
        }

        int dimensions = min.getDimensions();
        if (max.getDimensions() != dimensions) {
            throw new IllegalArgumentException("Vector dimensions must match (min: " + dimensions + ", max: " + max.getDimensions() + ")");
        }

        List<KeyVector> checkpoints = new ArrayList<>();

        // Calculate interval for uniform distribution
        KeyVector size = subtract(max, min);
        KeyVector interval = divide(size, count);

        KeyVector current = min;
        for (int i = 0; i < count; i++) {
            checkpoints.add(current);
            current = add(current, interval);
        }
        checkpoints.add(max);

        return checkpoints;
    }

    /**
     * Subtract two vectors element-wise.
     */
    private KeyVector subtract(KeyVector a, KeyVector b) {
        int dimensions = a.getDimensions();
        if (b.getDimensions() != dimensions) {
            throw new IllegalArgumentException("Vector dimensions must match (a: " + dimensions + ", b: " + b.getDimensions() + ")");
        }

        List<Comparable> result = new ArrayList<>();
        for (int i = 0; i < dimensions; i++) {
            result.add(subtractValues(a.getValue(i), b.getValue(i)));
        }
        return new KeyVector(result);
    }

    /**
     * Add two vectors element-wise.
     */
    private KeyVector add(KeyVector a, KeyVector b) {
        int dimensions = a.getDimensions();
        if (b.getDimensions() != dimensions) {
            throw new IllegalArgumentException("Vector dimensions must match (a: " + dimensions + ", b: " + b.getDimensions() + ")");
        }

        List<Comparable> result = new ArrayList<>();
        for (int i = 0; i < dimensions; i++) {
            result.add(addValues(a.getValue(i), b.getValue(i)));
        }
        return new KeyVector(result);
    }

    /**
     * Divide vector by scalar.
     */
    private KeyVector divide(KeyVector vector, int scalar) {
        int dimensions = vector.getDimensions();
        List<Comparable> result = new ArrayList<>();
        for (int i = 0; i < dimensions; i++) {
            result.add(divideValue(vector.getValue(i), scalar));
        }
        return new KeyVector(result);
    }

    /**
     * Subtract two comparable values.
     */
    @SuppressWarnings("unchecked")
    private Comparable subtractValues(Comparable a, Comparable b) {
        if (a instanceof Number && b instanceof Number) {
            double result = ((Number) a).doubleValue() - ((Number) b).doubleValue();
            if (a instanceof Integer) {
                return (int) result;
            } else if (a instanceof Long) {
                return (long) result;
            } else {
                return result;
            }
        } else if (a instanceof String && b instanceof String) {
            // For string types, return 0 as we can't do arithmetic subtraction
            // The segmentation will use the first numeric dimension instead
            return 0;
        } else {
            throw new IllegalArgumentException("Unsupported type for subtraction: " + a.getClass());
        }
    }

    /**
     * Add two comparable values.
     */
    @SuppressWarnings("unchecked")
    private Comparable addValues(Comparable a, Comparable b) {
        if (a instanceof Number && b instanceof Number) {
            double result = ((Number) a).doubleValue() + ((Number) b).doubleValue();
            if (a instanceof Integer) {
                return (int) result;
            } else if (a instanceof Long) {
                return (long) result;
            } else {
                return result;
            }
        } else if (a instanceof String && b instanceof String) {
            // For string types, just return the first value
            // The segmentation will use the first numeric dimension instead
            return a;
        } else {
            throw new IllegalArgumentException("Unsupported type for addition: " + a.getClass());
        }
    }

    /**
     * Divide comparable value by scalar.
     */
    @SuppressWarnings("unchecked")
    private Comparable divideValue(Comparable value, int scalar) {
        if (value instanceof Number) {
            double result = ((Number) value).doubleValue() / scalar;
            if (value instanceof Integer) {
                return (int) result;
            } else if (value instanceof Long) {
                return (long) result;
            } else {
                return result;
            }
        } else if (value instanceof String) {
            // For string types, just return the original value
            // The segmentation will use the first numeric dimension instead
            return value;
        } else {
            throw new IllegalArgumentException("Unsupported type for division: " + value.getClass());
        }
    }

    /**
     * Calculate the total size of the space between min and max.
     */
    public long calculateSpaceSize(KeyVector min, KeyVector max) {
        KeyVector size = subtract(max, min);
        long totalSize = 1;

        for (int i = 0; i < size.getDimensions(); i++) {
            Comparable dimSize = size.getValue(i);
            if (dimSize instanceof Number) {
                totalSize *= Math.max(1, ((Number) dimSize).longValue());
            } else if (dimSize instanceof BigInteger) {
                totalSize *= Math.max(1, ((BigInteger) dimSize).longValue());
            }
        }

        return totalSize;
    }
}