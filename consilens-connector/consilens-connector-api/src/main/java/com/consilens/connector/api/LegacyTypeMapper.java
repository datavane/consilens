package com.consilens.connector.api;

import com.consilens.common.enums.DataType;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.common.type.Types;

/**
 * Bridges legacy connector data types and the new logical type descriptor model.
 */
public final class LegacyTypeMapper {

    private LegacyTypeMapper() {
    }

    public static TypeDescriptor toTypeDescriptor(com.consilens.connector.api.model.DataType legacyType, String originType) {
        TypeDescriptor descriptor;
        if (legacyType == null) {
            descriptor = Types.UNKNOWN();
        } else {
            switch (legacyType) {
                case TINYINT:
                    descriptor = Types.TINYINT();
                    break;
                case SMALLINT:
                    descriptor = Types.SMALLINT();
                    break;
                case INTEGER:
                    descriptor = Types.INT();
                    break;
                case BIGINT:
                    descriptor = Types.BIGINT();
                    break;
                case FLOAT:
                case REAL:
                    descriptor = Types.FLOAT();
                    break;
                case DOUBLE:
                    descriptor = Types.DOUBLE();
                    break;
                case DECIMAL:
                case NUMERIC:
                    descriptor = TypeDescriptor.builder(DataType.DECIMAL_TYPE)
                            .originType(originType)
                            .numericPrecision(38)
                            .numericScale(0)
                            .build();
                    break;
                case CHAR:
                    descriptor = TypeDescriptor.builder(DataType.STRING_TYPE)
                            .originType(originType)
                            .build();
                    break;
                case VARCHAR:
                    descriptor = Types.STRING();
                    break;
                case TEXT:
                case CLOB:
                case LONGVARCHAR:
                    descriptor = TypeDescriptor.builder(DataType.STRING_TYPE)
                            .originType(originType)
                            .textType(true)
                            .build();
                    break;
                case DATE:
                    descriptor = Types.DATE();
                    break;
                case TIME:
                    descriptor = Types.TIME();
                    break;
                case TIME_WITH_TIME_ZONE:
                    descriptor = TypeDescriptor.builder(DataType.TIME_TYPE)
                            .originType(originType)
                            .withTimezone(true)
                            .build();
                    break;
                case DATETIME:
                case TIMESTAMP:
                    descriptor = Types.TIMESTAMP();
                    break;
                case TIMESTAMP_WITH_TIMEZONE:
                    descriptor = TypeDescriptor.builder(DataType.TIMESTAMP_TYPE)
                            .originType(originType)
                            .withTimezone(true)
                            .build();
                    break;
                case BOOLEAN:
                case BIT:
                    descriptor = Types.BOOLEAN();
                    break;
                case BINARY:
                case VARBINARY:
                    descriptor = Types.BINARY();
                    break;
                case BLOB:
                case LONGBLOB:
                    descriptor = TypeDescriptor.builder(DataType.BINARY_TYPE)
                            .originType(originType)
                            .blobType(true)
                            .build();
                    break;
                case JSON:
                case JSONB:
                    descriptor = Types.JSON();
                    break;
                case UUID:
                    descriptor = Types.UUID();
                    break;
                case ARRAY:
                    descriptor = TypeDescriptor.builder(DataType.ARRAY_TYPE)
                            .originType(originType)
                            .elementType(Types.UNKNOWN())
                            .build();
                    break;
                case OBJECT:
                    descriptor = Types.OBJECT();
                    break;
                case UNKNOWN:
                default:
                    descriptor = Types.UNKNOWN();
                    break;
            }
        }
        return descriptor.toBuilder().originType(originType).build();
    }

    public static com.consilens.connector.api.model.DataType toLegacyDataType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return com.consilens.connector.api.model.DataType.UNKNOWN;
        }
        switch (typeDescriptor.getType()) {
            case NULL_TYPE:
            case UNKNOWN_TYPE:
                return com.consilens.connector.api.model.DataType.UNKNOWN;
            case BOOLEAN_TYPE:
                return com.consilens.connector.api.model.DataType.BOOLEAN;
            case INTEGER_TYPE:
                Integer bitWidth = typeDescriptor.getBitWidth();
                if (bitWidth != null && bitWidth <= 8) {
                    return com.consilens.connector.api.model.DataType.TINYINT;
                }
                if (bitWidth != null && bitWidth <= 16) {
                    return com.consilens.connector.api.model.DataType.SMALLINT;
                }
                if (bitWidth != null && bitWidth >= 64) {
                    return com.consilens.connector.api.model.DataType.BIGINT;
                }
                return com.consilens.connector.api.model.DataType.INTEGER;
            case FLOAT_TYPE:
                return com.consilens.connector.api.model.DataType.FLOAT;
            case DOUBLE_TYPE:
                return com.consilens.connector.api.model.DataType.DOUBLE;
            case DECIMAL_TYPE:
                return com.consilens.connector.api.model.DataType.DECIMAL;
            case STRING_TYPE:
                return typeDescriptor.isTextType()
                        ? com.consilens.connector.api.model.DataType.TEXT
                        : com.consilens.connector.api.model.DataType.VARCHAR;
            case BINARY_TYPE:
                return typeDescriptor.isBlobType()
                        ? com.consilens.connector.api.model.DataType.BLOB
                        : com.consilens.connector.api.model.DataType.VARBINARY;
            case DATE_TYPE:
                return com.consilens.connector.api.model.DataType.DATE;
            case TIME_TYPE:
                return typeDescriptor.isWithTimezone()
                        ? com.consilens.connector.api.model.DataType.TIME_WITH_TIME_ZONE
                        : com.consilens.connector.api.model.DataType.TIME;
            case TIMESTAMP_TYPE:
                return typeDescriptor.isWithTimezone()
                        ? com.consilens.connector.api.model.DataType.TIMESTAMP_WITH_TIMEZONE
                        : com.consilens.connector.api.model.DataType.TIMESTAMP;
            case JSON_TYPE:
                return com.consilens.connector.api.model.DataType.JSON;
            case UUID_TYPE:
                return com.consilens.connector.api.model.DataType.UUID;
            case ARRAY_TYPE:
                return com.consilens.connector.api.model.DataType.ARRAY;
            case MAP_TYPE:
            case STRUCT_TYPE:
            case XML_TYPE:
            case ENUM_TYPE:
            case GEOMETRY_TYPE:
            case OBJECT_TYPE:
            case INTERVAL_TYPE:
            default:
                return com.consilens.connector.api.model.DataType.OBJECT;
        }
    }

    public static String toCanonicalType(TypeDescriptor typeDescriptor) {
        return toLegacyDataType(typeDescriptor).name().toLowerCase();
    }
}
