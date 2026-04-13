package com.consilens.connector.api.model.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents a vector of values, typically used for composite keys.
 */
public class Vector {

    private final Object[] values;
    private final int hash;

    public Vector(Object... values) {
        this.values = values != null ? values.clone() : new Object[0];
        this.hash = Arrays.hashCode(this.values);
    }

    public Vector(List<Object> values) {
        this(values != null ? values.toArray() : new Object[0]);
    }

    /**
     * Get the value at the given index.
     */
    public Object get(int index) {
        return values[index];
    }

    /**
     * Get the number of values in this vector.
     */
    public int size() {
        return values.length;
    }

    /**
     * Check if this vector is empty.
     */
    public boolean isEmpty() {
        return values.length == 0;
    }

    /**
     * Convert this vector to a list.
     */
    public List<Object> toList() {
        return new ArrayList<>(Arrays.asList(values));
    }

    /**
     * Convert this vector to an array.
     */
    public Object[] toArray() {
        return values.clone();
    }

    /**
     * Add another vector to this vector (element-wise).
     */
    public Vector add(Vector other) {
        if (this.size() != other.size()) {
            throw new IllegalArgumentException("Vector sizes must match for addition");
        }

        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = addValues(values[i], other.values[i]);
        }
        return new Vector(result);
    }

    /**
     * Subtract another vector from this vector (element-wise).
     */
    public Vector subtract(Vector other) {
        if (this.size() != other.size()) {
            throw new IllegalArgumentException("Vector sizes must match for subtraction");
        }

        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = subtractValues(values[i], other.values[i]);
        }
        return new Vector(result);
    }

    /**
     * Compare this vector with another vector.
     */
    public int compareTo(Vector other) {
        if (this.size() != other.size()) {
            return Integer.compare(this.size(), other.size());
        }

        for (int i = 0; i < values.length; i++) {
            int comparison = compareValues(values[i], other.values[i]);
            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    /**
     * Check if this vector is between min and max vectors (inclusive).
     */
    public boolean isBetween(Vector min, Vector max) {
        if (this.size() != min.size() || this.size() != max.size()) {
            throw new IllegalArgumentException("Vector sizes must match");
        }

        for (int i = 0; i < values.length; i++) {
            if (compareValues(values[i], min.get(i)) < 0 ||
                compareValues(values[i], max.get(i)) > 0) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Vector vector = (Vector) obj;
        return Arrays.equals(values, vector.values);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }

    // Helper methods for arithmetic operations

    private Object addValues(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) {
                return ((Number) a).doubleValue() + ((Number) b).doubleValue();
            } else if (a instanceof Float || b instanceof Float) {
                return ((Number) a).floatValue() + ((Number) b).floatValue();
            } else if (a instanceof Long || b instanceof Long) {
                return ((Number) a).longValue() + ((Number) b).longValue();
            } else {
                return ((Number) a).intValue() + ((Number) b).intValue();
            }
        } else if (a instanceof String && b instanceof String) {
            return a.toString() + b.toString();
        } else {
            // For other types, use string concatenation
            return a.toString() + b.toString();
        }
    }

    private Object subtractValues(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) {
                return ((Number) a).doubleValue() - ((Number) b).doubleValue();
            } else if (a instanceof Float || b instanceof Float) {
                return ((Number) a).floatValue() - ((Number) b).floatValue();
            } else if (a instanceof Long || b instanceof Long) {
                return ((Number) a).longValue() - ((Number) b).longValue();
            } else {
                return ((Number) a).intValue() - ((Number) b).intValue();
            }
        } else {
            throw new UnsupportedOperationException("Subtraction not supported for: " +
                a.getClass().getSimpleName() + " and " + b.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        } else if (a == null) {
            return -1;
        } else if (b == null) {
            return 1;
        }

        if (a instanceof Comparable && b instanceof Comparable) {
            try {
                return ((Comparable<Object>) a).compareTo(b);
            } catch (ClassCastException e) {
                // Fall back to string comparison
            }
        }

        // String comparison as fallback
        return a.toString().compareTo(b.toString());
    }

    /**
     * Create a new vector by scaling this vector by a factor.
     */
    public Vector scale(double factor) {
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Number) {
                result[i] = ((Number) values[i]).doubleValue() * factor;
            } else {
                result[i] = values[i];
            }
        }
        return new Vector(result);
    }

    /**
     * Calculate the average of two vectors.
     */
    public Vector average(Vector other) {
        return this.add(other).scale(0.5);
    }
}