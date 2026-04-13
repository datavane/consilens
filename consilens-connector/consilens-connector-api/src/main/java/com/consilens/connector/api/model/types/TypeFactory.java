package com.consilens.connector.api.model.types;

import com.consilens.connector.api.model.DataType;

/**
 * Factory for creating type objects from various inputs.
 * Bridges the gap between the old enum-based system and the new class-based system.
 */
public class TypeFactory {

    /**
     * Create a ColType from the legacy DataType enum.
     */
    public static ColType fromDataType(DataType dataType) {
        if (dataType == null) {
            return null;
        }

        switch (dataType) {
            // Integer types
            case TINYINT:
                return new IntegerType(3);
            case SMALLINT:
                return new IntegerType(5);
            case INTEGER:
                return new IntegerType(10);
            case BIGINT:
                return new IntegerType(19);

            // Fractional types
            case DECIMAL:
            case NUMERIC:
                return new FractionalType(38, 10, true); // Default precision and scale
            case FLOAT:
            case REAL:
                return new FractionalType(24, 0, true);
            case DOUBLE:
                return new FractionalType(53, 0, true);

            // String types
            case CHAR:
                return new Text(1, false);
            case VARCHAR:
                return new Text(255, false);
            case TEXT:
            case CLOB:
            case LONGVARCHAR:
                return new Text(-1, false);

            // Binary types
            case BINARY:
                return new Text(1, true);
            case VARBINARY:
                return new Text(255, true);
            case BLOB:
            case LONGBLOB:
                return new Text(-1, true);

            // Temporal types
            case DATE:
                return new Timestamp(0, false);
            case TIME:
                return new Timestamp(6, false);
            case TIMESTAMP:
            case DATETIME:
                return new Timestamp(6, false);
            case TIMESTAMP_WITH_TIMEZONE:
                return new Timestamp(6, false); // For simplicity, same as TIMESTAMP

            // Special types
            case BOOLEAN:
            case BIT:
                return new IntegerType(1); // Boolean as integer

            case JSON:
                return new Text(-1, false); // JSON as text
            case UUID:
                return new Text(36, false); // UUID as 36-character string
            case ARRAY:
            case OBJECT:
                return new Text(-1, false); // Arrays and objects as text

            default:
                return new Text(-1, false); // Unknown as text
        }
    }

    /**
     * Create a ColType from database type information.
     */
    public static ColType fromDatabaseType(String typeName, int precision, int scale) {
        if (typeName == null) {
            return new Text(-1, false);
        }

        String normalizedType = typeName.toUpperCase().trim();

        // Remove any parameters from the type name
        int parenIndex = normalizedType.indexOf('(');
        if (parenIndex > 0) {
            normalizedType = normalizedType.substring(0, parenIndex).trim();
        }

        switch (normalizedType) {
            // Integer types
            case "TINYINT":
            case "SMALLINT":
            case "INT":
            case "INTEGER":
            case "BIGINT":
                return new IntegerType(precision > 0 ? precision : 19);

            // Fractional types
            case "DECIMAL":
            case "NUMERIC":
                return new FractionalType(precision > 0 ? precision : 38, scale >= 0 ? scale : 10, true);
            case "FLOAT":
            case "REAL":
                return new FractionalType(precision > 0 ? precision : 24, 0, true);
            case "DOUBLE":
            case "DOUBLE PRECISION":
                return new FractionalType(precision > 0 ? precision : 53, 0, true);

            // String types
            case "CHAR":
                return new Text(precision > 0 ? precision : 1, false);
            case "VARCHAR":
            case "VARCHAR2":
                return new Text(precision > 0 ? precision : 255, false);
            case "TEXT":
            case "CLOB":
            case "LONGTEXT":
            case "LONGVARCHAR":
                return new Text(-1, false);

            // Binary types
            case "BINARY":
                return new Text(precision > 0 ? precision : 1, true);
            case "VARBINARY":
                return new Text(precision > 0 ? precision : 255, true);
            case "BLOB":
            case "LONGBLOB":
                return new Text(-1, true);

            // Temporal types
            case "DATE":
                return new Timestamp(0, false);
            case "TIME":
                return new Timestamp(precision > 0 ? precision : 6, false);
            case "TIMESTAMP":
            case "DATETIME":
                return new Timestamp(precision > 0 ? precision : 6, false);
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMPTZ":
                return new Timestamp(precision > 0 ? precision : 6, false);

            // Special types
            case "BOOLEAN":
            case "BIT":
                return new IntegerType(1);

            case "JSON":
            case "JSONB":
                return new Text(-1, false);

            case "UUID":
            case "UNIQUEIDENTIFIER":
                return new Text(36, false);

            default:
                return new Text(-1, false);
        }
    }

    /**
     * Create a key type from the given type.
     * Returns null if the type cannot be used as a key.
     */
    public static IKey createKeyType(ColType type) {
        if (type instanceof IKey) {
            return (IKey) type;
        }

        // Some types can be converted to keys
        if (type instanceof NumericType) {
            if (type instanceof IntegerType) {
                return (IntegerType) type;
            } else if (type instanceof FractionalType) {
                FractionalType fractional = (FractionalType) type;
                return new FractionalType(fractional.getPrecision(), fractional.getScale(), fractional.roundsOnPrecisionLoss());
            }
        }

        return null;
    }

    /**
     * Check if a type can be used as a key.
     */
    public static boolean canBeKeyType(ColType type) {
        return createKeyType(type) != null;
    }
}