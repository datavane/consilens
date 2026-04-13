package com.consilens.connector.api.model.types;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Timestamp type implementation.
 */
public class Timestamp extends TemporalType implements IKey {

    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PRECISION_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public Timestamp() {
        this(6, false); // Default precision 6 (microseconds)
    }

    public Timestamp(int precision) {
        this(precision, false);
    }

    public Timestamp(int precision, boolean rounds) {
        super(precision, rounds);
    }

    @Override
    public String toString() {
        if (precision == 6) {
            return "TIMESTAMP";
        } else {
            return String.format("TIMESTAMP(%d)", precision);
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

        if (value instanceof Instant) {
            return value;
        }

        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        }

        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant();
        }

        if (value instanceof String) {
            return parseValue((String) value);
        }

        return null;
    }

    @Override
    public Object getMinValue() {
        return Instant.MIN;
    }

    @Override
    public Object getMaxValue() {
        return Instant.MAX;
    }

    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null || stringValue.trim().isEmpty()) {
            return null;
        }

        try {
            // Try parsing with precision first
            if (precision > 0) {
                return LocalDateTime.parse(stringValue, PRECISION_FORMATTER).toInstant(ZoneOffset.UTC);
            } else {
                return LocalDateTime.parse(stringValue, DEFAULT_FORMATTER).toInstant(ZoneOffset.UTC);
            }
        } catch (Exception e) {
            try {
                // Fallback to Instant parsing
                return Instant.parse(stringValue);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @Override
    public String formatValue(Object value) {
        if (value == null) {
            return null;
        }

        Instant instant = null;
        if (value instanceof Instant) {
            instant = (Instant) value;
        } else if (value instanceof LocalDateTime) {
            instant = ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        } else if (value instanceof java.sql.Timestamp) {
            instant = ((java.sql.Timestamp) value).toInstant();
        }

        if (instant == null) {
            return null;
        }

        if (precision > 0) {
            return PRECISION_FORMATTER.format(instant.atZone(ZoneOffset.UTC));
        } else {
            return DEFAULT_FORMATTER.format(instant.atZone(ZoneOffset.UTC));
        }
    }

    @Override
    public TemporalType withPrecision(int precision) {
        return new Timestamp(precision, rounds);
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