package com.consilens.connector.api.model.types;

/**
 * Interface for types that can be used as keys.
 */
public interface IKey extends ColType {

    /**
     * Create a value of this key type from the given input.
     * This is used for normalization and comparison.
     */
    Object makeValue(Object value);

    /**
     * Get the minimum value for this key type.
     */
    Object getMinValue();

    /**
     * Get the maximum value for this key type.
     */
    Object getMaxValue();

    /**
     * Check if this key type supports range operations.
     */
    default boolean supportsRange() {
        return true;
    }

    /**
     * Check if this key type can be ordered.
     */
    default boolean isOrderable() {
        return true;
    }

    /**
     * Get the next value after the given value.
     * Used for creating checkpoints and ranges.
     */
    default Object getNextValue(Object value) {
        return null; // Default implementation doesn't support
    }

    /**
     * Convert a string representation to a value of this type.
     */
    Object parseValue(String stringValue);
}