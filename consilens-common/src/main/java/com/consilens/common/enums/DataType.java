package com.consilens.common.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * Stable logical type families used across connectors and sinks.
 */
public enum DataType {
    NULL_TYPE("NULL", TypeCategory.PRIMITIVE),
    BOOLEAN_TYPE("BOOLEAN", TypeCategory.PRIMITIVE),

    INTEGER_TYPE("INTEGER", TypeCategory.NUMERIC),
    FLOAT_TYPE("FLOAT", TypeCategory.NUMERIC),
    DOUBLE_TYPE("DOUBLE", TypeCategory.NUMERIC),
    DECIMAL_TYPE("DECIMAL", TypeCategory.NUMERIC),

    STRING_TYPE("STRING", TypeCategory.STRING),
    BINARY_TYPE("BINARY", TypeCategory.BINARY),

    DATE_TYPE("DATE", TypeCategory.DATETIME),
    TIME_TYPE("TIME", TypeCategory.DATETIME),
    TIMESTAMP_TYPE("TIMESTAMP", TypeCategory.DATETIME),
    INTERVAL_TYPE("INTERVAL", TypeCategory.DATETIME),

    ARRAY_TYPE("ARRAY", TypeCategory.COMPLEX),
    MAP_TYPE("MAP", TypeCategory.COMPLEX),
    STRUCT_TYPE("STRUCT", TypeCategory.COMPLEX),

    JSON_TYPE("JSON", TypeCategory.SEMI_STRUCTURED),
    XML_TYPE("XML", TypeCategory.SEMI_STRUCTURED),

    UUID_TYPE("UUID", TypeCategory.SPECIAL),
    GEOMETRY_TYPE("GEOMETRY", TypeCategory.SPECIAL),
    ENUM_TYPE("ENUM", TypeCategory.SPECIAL),
    OBJECT_TYPE("OBJECT", TypeCategory.COMPLEX),
    UNKNOWN_TYPE("UNKNOWN", TypeCategory.UNKNOWN);

    private static final Map<String, DataType> LOOKUP = new HashMap<>();

    static {
        for (DataType dataType : values()) {
            LOOKUP.put(dataType.name(), dataType);
            LOOKUP.put(dataType.typeName, dataType);
        }
    }

    private final String typeName;
    private final TypeCategory category;

    DataType(String typeName, TypeCategory category) {
        this.typeName = typeName;
        this.category = category;
    }

    public String getTypeName() {
        return typeName;
    }

    public TypeCategory getCategory() {
        return category;
    }

    public boolean isNumeric() {
        return category == TypeCategory.NUMERIC;
    }

    public boolean isString() {
        return category == TypeCategory.STRING;
    }

    public boolean isDateTime() {
        return category == TypeCategory.DATETIME;
    }

    public boolean isComplex() {
        return category == TypeCategory.COMPLEX;
    }

    public static DataType of(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_TYPE;
        }
        return LOOKUP.getOrDefault(value.trim().toUpperCase(), UNKNOWN_TYPE);
    }

    public enum TypeCategory {
        PRIMITIVE,
        NUMERIC,
        STRING,
        BINARY,
        DATETIME,
        COMPLEX,
        SEMI_STRUCTURED,
        SPECIAL,
        UNKNOWN
    }
}
