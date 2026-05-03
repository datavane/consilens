package com.consilens.connector.starrocks;

import com.consilens.common.type.StructField;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StarRocks data type handler.
 * 
 * <p>
 * Provides StarRocks-specific data type normalization and conversion:
 * <ul>
 * <li>Column normalization for checksum calculation</li>
 * <li>Data type mapping from source to StarRocks types</li>
 * <li>Timestamp formatting for StarRocks</li>
 * </ul>
 * 
 * @since 1.0.0
 */
@Slf4j
public class StarRocksDataTypeHandler extends BaseDataTypeHandler {

    public StarRocksDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new StarRocks data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public StarRocksDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        if (originType == null || originType.isBlank()) {
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE)
                    .originType(originType)
                    .build();
        }
        String upperType = normalizeTypeExpression(originType);
        if (upperType.startsWith("ARRAY<") && upperType.endsWith(">")) {
            TypeDescriptor elementType = convertToTypeDescriptor(originType.substring(originType.indexOf('<') + 1, originType.lastIndexOf('>')));
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE)
                    .originType(originType)
                    .elementType(elementType)
                    .build();
        }
        if (upperType.startsWith("MAP<") && upperType.endsWith(">")) {
            List<String> arguments = splitTopLevel(originType.substring(originType.indexOf('<') + 1, originType.lastIndexOf('>')));
            TypeDescriptor keyType = arguments.size() > 0 ? convertToTypeDescriptor(arguments.get(0)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            TypeDescriptor valueType = arguments.size() > 1 ? convertToTypeDescriptor(arguments.get(1)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.MAP_TYPE)
                    .originType(originType)
                    .keyType(keyType)
                    .valueType(valueType)
                    .build();
        }
        if (upperType.startsWith("STRUCT<") && upperType.endsWith(">")) {
            List<StructField> fields = new ArrayList<>();
            for (String part : splitTopLevel(originType.substring(originType.indexOf('<') + 1, originType.lastIndexOf('>')))) {
                int separator = findTopLevelCharacter(part, ':');
                if (separator <= 0) {
                    continue;
                }
                fields.add(StructField.builder(part.substring(0, separator).trim(), convertToTypeDescriptor(part.substring(separator + 1).trim())).build());
            }
            if (!fields.isEmpty()) {
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRUCT_TYPE)
                        .originType(originType)
                        .fields(fields)
                        .build();
            }
        }

        String baseType = extractBaseType(upperType);
        switch (baseType) {
            case "TINYINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(8).build();
            case "SMALLINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).build();
            case "INT":
            case "INTEGER":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
            case "BIGINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).build();
            case "LARGEINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(128).build();
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "DOUBLE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "DECIMAL":
            case "DECIMALV2": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 10)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 0)
                        .build();
            }
            case "CHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType) != null ? extractLength(upperType) : 1).build();
            case "VARCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType)).build();
            case "STRING":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).textType(true).build();
            case "BOOLEAN":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE).originType(originType).build();
            case "DATE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE).originType(originType).build();
            case "DATETIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).build();
            case "JSON":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.JSON_TYPE).originType(originType).build();
            case "HLL":
            case "BITMAP":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).blobType(true).build();
            default:
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "STRING";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE:
                if (typeDescriptor.getBitWidth() == null || typeDescriptor.getBitWidth() <= 8) return "TINYINT";
                if (typeDescriptor.getBitWidth() <= 16) return "SMALLINT";
                if (typeDescriptor.getBitWidth() <= 32) return "INT";
                if (typeDescriptor.getBitWidth() <= 64) return "BIGINT";
                return "LARGEINT";
            case FLOAT_TYPE:
                return "FLOAT";
            case DOUBLE_TYPE:
                return "DOUBLE";
            case DECIMAL_TYPE:
                return "DECIMAL(" + (typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 10)
                        + "," + (typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0) + ")";
            case BOOLEAN_TYPE:
                return "BOOLEAN";
            case STRING_TYPE:
                if (typeDescriptor.getLength() != null && !typeDescriptor.isTextType()) {
                    return "VARCHAR(" + typeDescriptor.getLength() + ")";
                }
                return "STRING";
            case BINARY_TYPE:
                return "STRING";
            case DATE_TYPE:
                return "DATE";
            case TIMESTAMP_TYPE:
                return "DATETIME";
            case JSON_TYPE:
                return "JSON";
            case ARRAY_TYPE:
                return "ARRAY<" + convertToOriginType(typeDescriptor.getElementType()) + ">";
            case MAP_TYPE:
                return "MAP<" + convertToOriginType(typeDescriptor.getKeyType()) + ", "
                        + convertToOriginType(typeDescriptor.getValueType()) + ">";
            case STRUCT_TYPE: {
                StringBuilder builder = new StringBuilder("STRUCT<");
                List<StructField> fields = typeDescriptor.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(fields.get(i).getName()).append(':')
                            .append(convertToOriginType(fields.get(i).getTypeDescriptor()));
                }
                return builder.append('>').toString();
            }
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * StarRocks-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), '')";
    }

    /**
     * StarRocks-specific integer normalization: CAST to CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '0')";
    }

    /**
     * StarRocks-specific decimal normalization with configurable decimal places.
     * StarRocks doesn't support FORMAT function like MySQL, so we use CAST to DECIMAL.
     * CRITICAL: CAST to DECIMAL will preserve trailing zeros when converted to CHAR.
     */
    @Override
    protected String normalizeDecimal(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("decimal", 4);
        // Get rounding from config, default to true (round half up)
        boolean rounding = getRounding("decimal", true);
        
        if (precision == 0) {
            // Return integer format without decimal point
            if (rounding) {
                return "COALESCE(TRIM(CAST(ROUND(" + quotedCol + ", 0) AS CHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(" + quotedCol + ", 0) AS CHAR)), '0')";
            }
        }
        
        String defaultValue = "0." + "0".repeat(precision);
        if (rounding) {
            // CAST to DECIMAL(38,precision) then to CHAR
            // StarRocks preserves trailing zeros when casting DECIMAL to CHAR
            return "COALESCE(CAST(CAST(" + quotedCol + " AS DECIMAL(38," + precision + ")) AS CHAR), '" + defaultValue + "')";
        } else {
            // StarRocks TRUNCATE + CAST: truncate first, then convert
            return "COALESCE(CAST(CAST(TRUNCATE(" + quotedCol + ", " + precision + ") AS DECIMAL(38," + precision + ")) AS CHAR), '" + defaultValue + "')";
        }
    }

    /**
     * StarRocks-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to DOUBLE then to DECIMAL to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding from config, default to true (round half up)
        boolean rounding = getRounding("float", true);
        
        if (precision == 0) {
            // Return integer format without decimal point
            if (rounding) {
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '0')";
            }
        }
        
        String defaultValue = "0." + "0".repeat(precision);
        if (rounding) {
            // Cast FLOAT to DOUBLE first, then to DECIMAL(38,precision), then to CHAR
            // StarRocks preserves trailing zeros when casting DECIMAL to CHAR
            return "COALESCE(CAST(CAST(CAST(" + quotedCol + " AS DOUBLE) AS DECIMAL(38," + precision + ")) AS CHAR), '" + defaultValue + "')";
        } else {
            // StarRocks TRUNCATE + CAST: truncate first, then convert
            return "COALESCE(CAST(CAST(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + ") AS DECIMAL(38," + precision + ")) AS CHAR), '" + defaultValue + "')";
        }
    }

    /**
     * StarRocks-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '%Y-%m-%d'), '')";
    }

    /**
     * StarRocks-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '%H:%i:%s'), '')";
    }

    /**
     * StarRocks-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * For DATETIME type (no timezone information):
     * - No timezone conversion needed, just format directly
     * CRITICAL: StarRocks stores DATETIME in local timezone, need to convert to UTC
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        // StarRocks DATETIME is timezone-aware, convert to UTC
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", 'Asia/Shanghai', '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * StarRocks-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMP type:
     * - Convert to UTC timezone before formatting
     * - This matches MySQL and PostgreSQL behavior
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", 'Asia/Shanghai', '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * StarRocks-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * StarRocks-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * StarRocks-specific blob normalization: convert to uppercase hexadecimal.
     * CRITICAL: Ensure HEX() function works correctly in StarRocks.
     * The result should be read as a STRING in the ResultSet, not as byte[].
     * CAST to CHAR to ensure it's treated as a string type.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        String normalized = "COALESCE(CAST(UPPER(HEX(" + quotedCol + ")) AS CHAR), '')";
        log.debug("StarRocks BLOB normalization for {}: {}", quotedCol, normalized);
        return normalized;
    }

    /**
     * StarRocks-specific JSON normalization: convert to CHAR.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS CHAR), '')";
    }

    /**
     * StarRocks-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '0')";
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null) {
            return DataType.UNKNOWN;
        }
        String type = sourceType.toLowerCase();

        switch (type) {
            case "tinyint":
                return DataType.TINYINT;
            case "smallint":
                return DataType.SMALLINT;
            case "int":
            case "integer":
                return DataType.INTEGER;
            case "bigint":
                return DataType.BIGINT;
            case "decimal":
            case "numeric":
                return DataType.DECIMAL;
            case "float":
                return DataType.FLOAT;
            case "double":
                return DataType.DOUBLE;
            case "varchar":
                return DataType.VARCHAR;
            case "char":
                return DataType.CHAR;
            case "string":
                return DataType.TEXT;
            case "text":
                return DataType.TEXT;
            case "date":
                return DataType.DATE;
            case "datetime":
                return DataType.DATETIME;
            case "timestamp":
                return DataType.TIMESTAMP;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "json":
                return DataType.JSON;
            case "array":
            case "map":
            case "struct":
                // Semi-structured types map to TEXT for compatibility
                return DataType.TEXT;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "STRING"; // StarRocks uses STRING for large text
            case TINYINT:
                return "TINYINT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INT";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case FLOAT:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case DATETIME:
                return "DATETIME";
            case TIMESTAMP:
                return "DATETIME"; // StarRocks uses DATETIME for timestamps
            case BOOLEAN:
                return "TINYINT(1)"; // StarRocks compatibility - BOOLEAN stored as TINYINT(1)
            case JSON:
                return "JSON";
            default:
                return super.formatDataType(dataType, length, precision, scale);
        }
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null) {
            return "NULL";
        }
        return timestamp.toString();
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value;
    }
}
