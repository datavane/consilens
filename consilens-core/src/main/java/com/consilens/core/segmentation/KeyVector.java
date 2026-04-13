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
        if (values.size() != other.values.size()) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        for (int i = 0; i < values.size(); i++) {
            int cmp = values.get(i).compareTo(other.values.get(i));
            if (cmp < 0) {
                return true;
            } else if (cmp > 0) {
                return false;
            }
        }
        return false; // vectors are equal
    }

    /**
     * Check if this vector is less than or equal to another vector.
     */
    public boolean isLessThanOrEqual(KeyVector other) {
        return isLessThan(other) || equals(other);
    }

    /**
     * Check if this vector is greater than another vector.
     */
    public boolean isGreaterThan(KeyVector other) {
        return !isLessThanOrEqual(other);
    }

    /**
     * Check if this vector is greater than or equal to another vector.
     */
    public boolean isGreaterThanOrEqual(KeyVector other) {
        return !isLessThan(other);
    }

    /**
     * Convert to string representation.
     */
    @Override
    public String toString() {
        return values.toString();
    }

    /**
     * Convert to array.
     */
    public Object[] toArray() {
        return values.toArray();
    }

    /**
     * Convert to list.
     */
    public List<Comparable> toList() {
        return new ArrayList<>(values);
    }

    /**
     * Create a copy with updated value at specified index.
     */
    public KeyVector withValue(int index, Comparable value) {
        List<Comparable> newValues = new ArrayList<>(values);
        newValues.set(index, value);
        return new KeyVector(newValues);
    }

    /**
     * Create a new vector with the same values but in reverse order.
     */
    public KeyVector reverse() {
        List<Comparable> reversed = new ArrayList<>(values);
        java.util.Collections.reverse(reversed);
        return new KeyVector(reversed);
    }

    /**
     * Get hash code for this vector.
     */
    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /**
     * Check equality with another vector.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        KeyVector keyVector = (KeyVector) obj;
        return values.equals(keyVector.values);
    }
}