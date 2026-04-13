package com.consilens.connector.api.model.types;

import java.util.Objects;

/**
 * Numeric type with fractional part (decimal numbers).
 */
public class FractionalType extends NumericType implements IKey {

    private final boolean roundsOnPrecLoss;

    public FractionalType(int precision, int scale) {
        this(precision, scale, false);
    }

    public FractionalType(int precision, int scale, boolean roundsOnPrecLoss) {
        super(precision, scale);
        this.roundsOnPrecLoss = roundsOnPrecLoss;
    }

    @Override
    public boolean roundsOnPrecisionLoss() {
        return roundsOnPrecLoss;
    }

    @Override
    public String toString() {
        if (scale > 0) {
            return String.format("DECIMAL(%d,%d)", precision, scale);
        } else {
            return String.format("DECIMAL(%d)", precision);
        }
    }

    @Override
    public String toSql() {
        return toString();
    }

    @Override
    public Object makeValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return value;
        }

        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    @Override
    public Object getMinValue() {
        return Double.MIN_VALUE;
    }

    @Override
    public Object getMaxValue() {
        return Double.MAX_VALUE;
    }

    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(stringValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public NumericType withPrecision(int precision, int scale) {
        return new FractionalType(precision, scale, roundsOnPrecLoss);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (!super.equals(obj)) return false;
        FractionalType that = (FractionalType) obj;
        return roundsOnPrecLoss == that.roundsOnPrecLoss;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), roundsOnPrecLoss);
    }
}