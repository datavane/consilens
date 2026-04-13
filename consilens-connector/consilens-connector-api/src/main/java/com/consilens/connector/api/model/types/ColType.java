package com.consilens.connector.api.model.types;

/**
 * Base interface for all data types in the diff system.
 */
public interface ColType {

    /**
     * Get the string representation of this type.
     */
    String toString();

    /**
     * Get the SQL representation of this type.
     */
    default String toSql() {
        return toString();
    }

    /**
     * Check if this type is compatible with another type.
     */
    default boolean isCompatible(ColType other) {
        return this.equals(other);
    }

    /**
     * Create a copy of this type with given parameters.
     */
    default ColType copy() {
        return this;
    }

    /**
     * Get the precision of this type, if applicable.
     */
    default int getPrecision() {
        return -1; // -1 means not applicable
    }

    /**
     * Get the scale of this type, if applicable.
     */
    default int getScale() {
        return -1; // -1 means not applicable
    }

    /**
     * Check if this type rounds on precision loss.
     */
    default boolean roundsOnPrecisionLoss() {
        return false;
    }

    /**
     * Get the simple name of this type.
     */
    default String getSimpleName() {
        return this.getClass().getSimpleName();
    }

    @Override
    boolean equals(Object obj);

    @Override
    int hashCode();
}