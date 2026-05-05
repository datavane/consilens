package com.consilens.connector.api.model;

import lombok.Getter;

/**
 * Enumeration for supported data types.
 */
@Getter
public enum DataType {

    // Numeric types
    TINYINT("TINYINT", true, false),
    SMALLINT("SMALLINT", true, false),
    INTEGER("INTEGER", true, false),
    BIGINT("BIGINT", true, false),
    DECIMAL("DECIMAL", true, false),
    NUMERIC("NUMERIC", true, false),
    FLOAT("FLOAT", true, false),
    DOUBLE("DOUBLE", true, false),
    REAL("REAL", true, false),

    // String types
    CHAR("CHAR", false, true),
    VARCHAR("VARCHAR", false, true),
    TEXT("TEXT", false, true),
    CLOB("CLOB", false, true),
    LONGVARCHAR("LONGVARCHAR", false, true),

    // Date and time types
    DATE("DATE", false, false),
    TIME("TIME", false, false),
    TIMESTAMP("TIMESTAMP", false, false),
    DATETIME("DATETIME", false, false),
    TIMESTAMP_WITH_TIMEZONE("TIMESTAMP WITH TIME ZONE", false, false),
    TIME_WITH_TIME_ZONE("TIME WITH TIME ZONE", false, false),

    // Boolean types
    BOOLEAN("BOOLEAN", false, false),
    BIT("BIT", false, false),

    // Binary types
    BINARY("BINARY", false, true),
    VARBINARY("VARBINARY", false, true),
    BLOB("BLOB", false, true),
    LONGBLOB("LONGBLOB", false, true),

    // Special types
    JSON("JSON", false, false),
    JSONB("JSONB", false, false),
    UUID("UUID", false, false),
    ARRAY("ARRAY", false, false),
    OBJECT("OBJECT", false, false),

    // Unknown type
    UNKNOWN("UNKNOWN", false, false);

    private final String displayName;
    private final boolean numeric;
    private final boolean string;

    DataType(String displayName, boolean numeric, boolean string) {
        this.displayName = displayName;
        this.numeric = numeric;
        this.string = string;
    }

    public boolean isTemporal() {
        return this == DATE || this == TIME || this == TIMESTAMP ||
                this == DATETIME || this == TIMESTAMP_WITH_TIMEZONE ||
                this == TIME_WITH_TIME_ZONE;
    }

    public boolean isBinary() {
        return this == BINARY || this == VARBINARY || this == BLOB || this == LONGBLOB;
    }

    /**
     * Check if this type is compatible with another type.
     */
    public boolean isCompatibleWith(DataType other) {
        if (this == other) {
            return true;
        }

        if (other == UNKNOWN) {
            return true; // Unknown types are considered compatible
        }

        // Numeric type compatibility
        if (this.isNumeric() && other.isNumeric()) {
            return true; // All numeric types are considered compatible
        }

        // String type compatibility
        if (this.isString() && other.isString()) {
            return true; // All string types are considered compatible
        }

        // Temporal type compatibility
        if (this.isTemporal() && other.isTemporal()) {
            return true; // All temporal types are considered compatible
        }

        // Boolean compatibility
        if ((this == BOOLEAN && other == BOOLEAN) ||
                (this == BIT && other == BIT)) {
            return true;
        }

        // JSON compatibility
        if ((this == JSON && other == JSON) ||
                (this == OBJECT && other == OBJECT)) {
            return true;
        }

        return false;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
