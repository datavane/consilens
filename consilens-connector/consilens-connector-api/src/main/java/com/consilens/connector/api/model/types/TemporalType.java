package com.consilens.connector.api.model.types;

import java.util.Objects;

/**
 * Base class for temporal (date/time) types.
 */
public abstract class TemporalType implements ColType {

    protected final int precision;
    protected final boolean rounds;

    protected TemporalType(int precision, boolean rounds) {
        this.precision = precision;
        this.rounds = rounds;
    }

    @Override
    public int getPrecision() {
        return precision;
    }

    @Override
    public boolean roundsOnPrecisionLoss() {
        return rounds;
    }

    @Override
    public boolean isCompatible(ColType other) {
        if (this.equals(other)) {
            return true;
        }

        // All temporal types are compatible with each other
        return other instanceof TemporalType;
    }

    /**
     * Parse a string value into a temporal object.
     */
    public abstract Object parseValue(String value);

    /**
     * Format a temporal value as a string.
     */
    public abstract String formatValue(Object value);

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        TemporalType that = (TemporalType) obj;
        return precision == that.precision && rounds == that.rounds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(precision, rounds);
    }

    /**
     * Create a copy of this temporal type with new precision.
     */
    public abstract TemporalType withPrecision(int precision);
}