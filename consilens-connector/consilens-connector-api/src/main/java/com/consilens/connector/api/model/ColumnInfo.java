package com.consilens.connector.api.model;

import com.consilens.connector.api.model.types.ColType;
import com.consilens.connector.api.model.types.IKey;
import com.consilens.connector.api.model.types.TypeFactory;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Optional;

/**
 * Unified column information model.
 */
@Getter
@EqualsAndHashCode
@ToString
@Builder
public class ColumnInfo {

    private final String name;
    private final DataType type;
    private final boolean nullable;
    private final Optional<Integer> precision;
    private final Optional<Integer> scale;
    private final Optional<Integer> maxLength;
    private final Optional<String> defaultValue;
    private final Optional<String> collation;
    private final boolean primaryKey;
    private final boolean uniqueKey;
    private final Optional<String> comment;
    private final int ordinalPosition;

    /**
     * Constructor with validation.
     */
    private ColumnInfo(String name, DataType type, boolean nullable, Optional<Integer> precision,
            Optional<Integer> scale, Optional<Integer> maxLength, Optional<String> defaultValue,
            Optional<String> collation, boolean primaryKey, boolean uniqueKey,
            Optional<String> comment, int ordinalPosition) {

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Column name cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Data type cannot be null");
        }
        if (ordinalPosition < 1) {
            throw new IllegalArgumentException("Ordinal position must be >= 1");
        }

        this.name = name.trim();
        this.type = type;
        this.nullable = nullable;
        this.precision = precision;
        this.scale = scale;
        this.maxLength = maxLength;
        this.defaultValue = defaultValue.map(String::trim);
        this.collation = collation.map(String::trim);
        this.primaryKey = primaryKey;
        this.uniqueKey = uniqueKey;
        this.comment = comment;
        this.ordinalPosition = ordinalPosition;
    }

    /**
     * Create a simple column info.
     */
    public static ColumnInfo of(String name, DataType type) {
        return builder()
                .name(name)
                .type(type)
                .nullable(true)
                .ordinalPosition(1)
                .build();
    }

    /**
     * Create a column info with nullable constraint.
     */
    public static ColumnInfo of(String name, DataType type, boolean nullable) {
        return builder()
                .name(name)
                .type(type)
                .nullable(nullable)
                .ordinalPosition(1)
                .build();
    }

    /**
     * Create a primary key column info.
     */
    public static ColumnInfo primaryKey(String name, DataType type) {
        return builder()
                .name(name)
                .type(type)
                .nullable(false)
                .primaryKey(true)
                .ordinalPosition(1)
                .build();
    }

    /**
     * Create from database metadata (raw string type).
     * Replaces com.consilens.core.database.model.ColumnInfo usage.
     * This method supports creating ColumnInfo from raw database metadata where
     * types are strings.
     *
     * @param name         the column name
     * @param rawDataType  the raw data type string (e.g., "VARCHAR(50)",
     *                     "DECIMAL(12,2)")
     * @param maxLength    the maximum length for character types
     * @param precision    the precision for numeric types
     * @param scale        the scale for numeric types
     * @param nullable     whether the column allows NULL values
     * @param defaultValue the default value of the column (null if no default)
     * @return ColumnInfo instance
     */
    public static ColumnInfo fromDatabaseMetadata(
            String name,
            String rawDataType,
            int maxLength,
            int precision,
            int scale,
            boolean nullable,
            String defaultValue) {

        // Extract base type name without parameters (e.g., "VARCHAR(50)" -> "VARCHAR")
        String baseType = extractBaseType(rawDataType);
        DataType dataType = mapStringToDataType(baseType);

        return builder()
                .name(name)
                .type(dataType)
                .nullable(nullable)
                .maxLength(maxLength > 0 ? Optional.of(maxLength) : Optional.empty())
                .precision(precision > 0 ? Optional.of(precision) : Optional.empty())
                .scale(scale > 0 ? Optional.of(scale) : Optional.empty())
                .defaultValue(Optional.ofNullable(defaultValue))
                .ordinalPosition(1) // Will be updated later if needed
                .build();
    }

    /**
     * Extract base type name from a parameterized type string.
     * For example: "VARCHAR(50)" -> "VARCHAR", "DECIMAL(12,2)" -> "DECIMAL"
     *
     * @param rawType the raw type string
     * @return the base type name
     */
    private static String extractBaseType(String rawType) {
        if (rawType == null) {
            return "";
        }
        int parenIndex = rawType.indexOf('(');
        return parenIndex > 0 ? rawType.substring(0, parenIndex).trim() : rawType.trim();
    }

    /**
     * Map a string type name to DataType enum.
     * Handles various database-specific type names.
     *
     * @param baseType the base type name (e.g., "VARCHAR", "INT")
     * @return the corresponding DataType enum
     */
    private static DataType mapStringToDataType(String baseType) {
        String upperType = baseType.toUpperCase();

        // VARCHAR and string types
        if (upperType.startsWith("VARCHAR") || upperType.equals("VARCHAR2") ||
                upperType.equals("STRING") || upperType.startsWith("NVARCHAR")) {
            return DataType.VARCHAR;
        }

        // CHAR types
        if (upperType.startsWith("CHAR") || upperType.equals("CHARACTER") ||
                upperType.startsWith("NCHAR")) {
            return DataType.CHAR;
        }

        // TEXT types
        if (upperType.equals("TEXT") || upperType.equals("LONGTEXT") ||
                upperType.equals("MEDIUMTEXT") || upperType.equals("TINYTEXT") ||
                upperType.equals("CLOB") || upperType.equals("NCLOB")) {
            return DataType.TEXT;
        }

        // Integer types
        if (upperType.equals("INTEGER") || upperType.equals("INT") ||
                upperType.equals("INT4") || upperType.equals("MEDIUMINT")) {
            return DataType.INTEGER;
        }

        if (upperType.equals("BIGINT") || upperType.equals("INT8") ||
                upperType.equals("INT64")) {
            return DataType.BIGINT;
        }

        if (upperType.equals("SMALLINT") || upperType.equals("INT2")) {
            return DataType.SMALLINT;
        }

        if (upperType.equals("TINYINT")) {
            return DataType.TINYINT;
        }

        // Decimal/Numeric types
        if (upperType.equals("DECIMAL") || upperType.equals("NUMERIC") ||
                upperType.equals("BIGNUMERIC")) {
            return DataType.DECIMAL;
        }

        // Floating-point types
        if (upperType.equals("FLOAT") || upperType.equals("FLOAT4") ||
                upperType.equals("FLOAT64")) {
            return DataType.FLOAT;
        }

        if (upperType.equals("DOUBLE") || upperType.equals("DOUBLE PRECISION") ||
                upperType.equals("FLOAT8")) {
            return DataType.DOUBLE;
        }

        if (upperType.equals("REAL")) {
            return DataType.REAL;
        }

        // Date/Time types
        if (upperType.equals("DATE")) {
            return DataType.DATE;
        }

        if (upperType.equals("TIME")) {
            return DataType.TIME;
        }

        if (upperType.equals("DATETIME") || upperType.equals("TIMESTAMP") ||
                upperType.equals("TIMESTAMPTZ")) {
            return DataType.DATETIME;
        }

        // Boolean types
        if (upperType.equals("BOOLEAN") || upperType.equals("BOOL")) {
            return DataType.BOOLEAN;
        }

        if (upperType.equals("BIT")) {
            return DataType.BIT;
        }

        // Binary types
        if (upperType.equals("BLOB") || upperType.equals("LONGBLOB") ||
                upperType.equals("MEDIUMBLOB") || upperType.equals("TINYBLOB") ||
                upperType.equals("BYTES")) {
            return DataType.BLOB;
        }

        if (upperType.equals("BINARY")) {
            return DataType.BINARY;
        }

        if (upperType.equals("VARBINARY") || upperType.equals("BYTEA")) {
            return DataType.VARBINARY;
        }

        // JSON types
        if (upperType.equals("JSON") || upperType.equals("JSONB")) {
            return DataType.JSON;
        }

        // Default to UNKNOWN for unrecognized types
        return DataType.UNKNOWN;
    }

    /**
     * Get display name (quoted version).
     */
    public String getDisplayName(String quoteChar) {
        return quoteChar + name + quoteChar;
    }

    /**
     * Check if this is a numeric column.
     */
    public boolean isNumeric() {
        return type.isNumeric();
    }

    /**
     * Check if this is a string column.
     */
    public boolean isString() {
        return type.isString();
    }

    /**
     * Check if this is a temporal/date column.
     */
    public boolean isTemporal() {
        return type.isTemporal();
    }

    /**
     * Check if this is a binary column.
     */
    public boolean isBinary() {
        return type.isBinary();
    }

    /**
     * Get effective max length based on type.
     */
    public int getEffectiveMaxLength() {
        if (maxLength.isPresent()) {
            return maxLength.get();
        }

        // Default lengths based on data type
        switch (type) {
            case VARCHAR:
            case CHAR:
                return 255;
            case TEXT:
                return 65535;
            case CLOB:
                return Integer.MAX_VALUE;
            default:
                return 0;
        }
    }

    /**
     * Check if this column has a default value.
     */
    public boolean hasDefaultValue() {
        return defaultValue.isPresent();
    }

    /**
     * Check if this column has a collation.
     */
    public boolean hasCollation() {
        return collation.isPresent();
    }

    /**
     * Check if this column is part of a unique constraint.
     */
    public boolean isUnique() {
        return uniqueKey || primaryKey;
    }

    /**
     * Check if this is an auto-increment column.
     */
    public boolean isAutoIncrement() {
        if (defaultValue.isPresent()) {
            String defVal = defaultValue.get().toLowerCase();
            return defVal.contains("auto_increment") || defVal.contains("serial") || defVal.contains("identity");
        }
        return false;
    }

    /**
     * Create a copy with modified type.
     */
    public ColumnInfo withType(DataType newType) {
        return builder()
                .name(name)
                .type(newType)
                .nullable(nullable)
                .precision(precision)
                .scale(scale)
                .maxLength(maxLength)
                .defaultValue(defaultValue)
                .collation(collation)
                .primaryKey(primaryKey)
                .uniqueKey(uniqueKey)
                .comment(comment)
                .ordinalPosition(ordinalPosition)
                .build();
    }

    /**
     * Create a copy with modified nullable constraint.
     */
    public ColumnInfo withNullable(boolean newNullable) {
        return builder()
                .name(name)
                .type(type)
                .nullable(newNullable)
                .precision(precision)
                .scale(scale)
                .maxLength(maxLength)
                .defaultValue(defaultValue)
                .collation(collation)
                .primaryKey(primaryKey)
                .uniqueKey(uniqueKey)
                .comment(comment)
                .ordinalPosition(ordinalPosition)
                .build();
    }

    /**
     * Create a copy with new default value.
     */
    public ColumnInfo withDefaultValue(String newDefaultValue) {
        return builder()
                .name(name)
                .type(type)
                .nullable(nullable)
                .precision(precision)
                .scale(scale)
                .maxLength(maxLength)
                .defaultValue(Optional.ofNullable(newDefaultValue))
                .collation(collation)
                .primaryKey(primaryKey)
                .uniqueKey(uniqueKey)
                .comment(comment)
                .ordinalPosition(ordinalPosition)
                .build();
    }

    /**
     * Create a copy with new ordinal position.
     */
    public ColumnInfo withOrdinalPosition(int newPosition) {
        return builder()
                .name(name)
                .type(type)
                .nullable(nullable)
                .precision(precision)
                .scale(scale)
                .maxLength(maxLength)
                .defaultValue(defaultValue)
                .collation(collation)
                .primaryKey(primaryKey)
                .uniqueKey(uniqueKey)
                .comment(comment)
                .ordinalPosition(newPosition)
                .build();
    }

    /**
     * Get type information as string for display.
     */
    public String getDisplayType() {
        StringBuilder typeStr = new StringBuilder(type.toString());

        // Add precision/scale for numeric types
        if (type.isNumeric() && precision.isPresent()) {
            typeStr.append("(").append(precision.get());
            if (scale.isPresent()) {
                typeStr.append(",").append(scale.get());
            }
            typeStr.append(")");
        }

        // Add length for string types
        if (type.isString() && maxLength.isPresent()) {
            typeStr.append("(").append(maxLength.get()).append(")");
        }

        return typeStr.toString();
    }

    /**
     * Get the enhanced ColType representation.
     */
    public ColType getColType() {
        return TypeFactory.fromDataType(type);
    }

    /**
     * Check if this column can be used as a key in the new type system.
     */
    public boolean canBeKeyType() {
        return TypeFactory.canBeKeyType(getColType()) && !nullable;
    }

    /**
     * Get this column as a key type, or null if it cannot be a key.
     */
    public IKey asKeyType() {
        if (!canBeKeyType()) {
            return null;
        }
        return TypeFactory.createKeyType(getColType());
    }

    /**
     * Validate column information against constraints.
     */
    public ValidationResult validate() {
        // Check name format
        if (!name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return ValidationResult.invalid("Invalid column name: " + name);
        }

        // Check type constraints
        if (type == DataType.DECIMAL) {
            if (precision.isPresent() && precision.get() < 1) {
                return ValidationResult.invalid("Decimal type requires precision >= 1");
            }
            if (precision.isPresent() && scale.isPresent() && scale.get() > precision.get()) {
                return ValidationResult.invalid("Decimal scale cannot be greater than precision");
            }
        }

        if (type == DataType.VARCHAR || type == DataType.CHAR) {
            if (maxLength.isPresent() && maxLength.get() < 1) {
                return ValidationResult.invalid("String type requires max length >= 1");
            }
        }

        // Check primary key constraints
        if (primaryKey && nullable) {
            return ValidationResult.invalid("Primary key column cannot be nullable");
        }

        return ValidationResult.valid();
    }

    /**
     * Validation result.
     */
    @Getter
    @Builder
    @ToString
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}