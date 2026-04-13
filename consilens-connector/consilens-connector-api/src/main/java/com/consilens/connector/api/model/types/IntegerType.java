package com.consilens.connector.api.model.types;

/**
 * Integer numeric type.
 */
public class IntegerType extends NumericType implements IKey {

    public IntegerType() {
        this(10, 0); // Default precision for BIGINT
    }

    public IntegerType(int precision) {
        this(precision, 0);
    }

    public IntegerType(int precision, int scale) {
        super(precision, scale);
    }

    @Override
    public String toString() {
        if (precision <= 4) {
            return "SMALLINT";
        } else if (precision <= 9) {
            return "INTEGER";
        } else if (precision <= 18) {
            return "BIGINT";
        } else {
            return String.format("NUMERIC(%d)", precision);
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
            return ((Number) value).longValue();
        }

        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    @Override
    public Object getMinValue() {
        return Long.MIN_VALUE;
    }

    @Override
    public Object getMaxValue() {
        return Long.MAX_VALUE;
    }

    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }

        try {
            return Long.parseLong(stringValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public NumericType withPrecision(int precision, int scale) {
        return new IntegerType(precision, scale);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}