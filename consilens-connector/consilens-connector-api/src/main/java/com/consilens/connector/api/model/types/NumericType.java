package com.consilens.connector.api.model.types;

import java.util.Objects;

/**
 * Base class for numeric types.
 */
public abstract class NumericType implements ColType {

    protected final int precision;
    protected final int scale;

    protected NumericType(int precision, int scale) {
        this.precision = precision;
        this.scale = scale;
    }

    @Override
    public int getPrecision() {
        return precision;
    }

    @Override
    public int getScale() {
        return scale;
    }

    @Override
    public boolean isCompatible(ColType other) {
        if (this.equals(other)) {
            return true;
        }

        // All numeric types are compatible with each other
        return other instanceof NumericType;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        NumericType that = (NumericType) obj;
        return precision == that.precision && scale == that.scale;
    }

    @Override
    public int hashCode() {
        return Objects.hash(precision, scale);
    }

    /**
     * Create a copy of this numeric type with new precision and scale.
     */
    public abstract NumericType withPrecision(int precision, int scale);
}