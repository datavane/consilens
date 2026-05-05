package com.consilens.connector.presto;

import com.consilens.common.type.StructField;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Presto data type handler.
 */
public class PrestoDataTypeHandler extends BaseDataTypeHandler {

    public PrestoDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new Presto data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public PrestoDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        if (originType == null || originType.isBlank()) {
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).originType(originType).build();
        }
        String upperType = normalizeTypeExpression(originType);
        if (upperType.startsWith("ARRAY(") && upperType.endsWith(")")) {
            TypeDescriptor elementType = convertToTypeDescriptor(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE).originType(originType).elementType(elementType).build();
        }
        if (upperType.startsWith("MAP(") && upperType.endsWith(")")) {
            List<String> arguments = splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            TypeDescriptor keyType = arguments.size() > 0 ? convertToTypeDescriptor(arguments.get(0)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            TypeDescriptor valueType = arguments.size() > 1 ? convertToTypeDescriptor(arguments.get(1)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.MAP_TYPE).originType(originType).keyType(keyType).valueType(valueType).build();
        }
        if (upperType.startsWith("ROW(") && upperType.endsWith(")")) {
            List<StructField> fields = new ArrayList<>();
            List<String> parts = splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).trim();
                int separator = findTopLevelCharacter(part, ' ');
                if (separator > 0) {
                    fields.add(StructField.builder(part.substring(0, separator).trim().replace("\"", ""), convertToTypeDescriptor(part.substring(separator + 1).trim())).build());
                } else {
                    fields.add(StructField.builder("field_" + i, convertToTypeDescriptor(part)).build());
                }
            }
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRUCT_TYPE).originType(originType).fields(fields).build();
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
            case "REAL":
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "DOUBLE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "DECIMAL":
            case "NUMERIC": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 10)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 0)
                        .build();
            }
            case "VARCHAR":
            case "CHAR":
            case "STRING":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType)).build();
            case "VARBINARY":
            case "BINARY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).build();
            case "BOOLEAN":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE).originType(originType).build();
            case "DATE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE).originType(originType).build();
            case "TIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIME_TYPE).originType(originType).build();
            case "TIMESTAMP":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .withTimezone(upperType.contains("WITH TIME ZONE"))
                        .build();
            case "INTERVAL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTERVAL_TYPE).originType(originType).build();
            case "JSON":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.JSON_TYPE).originType(originType).build();
            default:
                if ("UUID".equals(upperType)) {
                    return TypeDescriptor.builder(com.consilens.common.enums.DataType.UUID_TYPE).originType(originType).build();
                }
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "VARCHAR";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE:
                if (typeDescriptor.getBitWidth() == null || typeDescriptor.getBitWidth() <= 8) return "TINYINT";
                if (typeDescriptor.getBitWidth() <= 16) return "SMALLINT";
                if (typeDescriptor.getBitWidth() <= 32) return "INTEGER";
                return "BIGINT";
            case FLOAT_TYPE:
                return "REAL";
            case DOUBLE_TYPE:
                return "DOUBLE";
            case DECIMAL_TYPE:
                return "DECIMAL(" + (typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 10)
                        + "," + (typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0) + ")";
            case STRING_TYPE:
                return typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0
                        ? "VARCHAR(" + typeDescriptor.getLength() + ")"
                        : "VARCHAR";
            case BINARY_TYPE:
                return "VARBINARY";
            case BOOLEAN_TYPE:
                return "BOOLEAN";
            case DATE_TYPE:
                return "DATE";
            case TIME_TYPE:
                return "TIME";
            case TIMESTAMP_TYPE:
                return typeDescriptor.isWithTimezone() ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
            case INTERVAL_TYPE:
                return "INTERVAL DAY TO SECOND";
            case JSON_TYPE:
                return "JSON";
            case UUID_TYPE:
                return "UUID";
            case ARRAY_TYPE:
                return "ARRAY(" + convertToOriginType(typeDescriptor.getElementType()) + ")";
            case MAP_TYPE:
                return "MAP(" + convertToOriginType(typeDescriptor.getKeyType()) + ", "
                        + convertToOriginType(typeDescriptor.getValueType()) + ")";
            case STRUCT_TYPE: {
                StringBuilder builder = new StringBuilder("ROW(");
                List<StructField> fields = typeDescriptor.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(fields.get(i).getName()).append(' ')
                            .append(convertToOriginType(fields.get(i).getTypeDescriptor()));
                }
                return builder.append(')').toString();
            }
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * Presto-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), '')";
    }

    /**
     * Presto-specific integer normalization: CAST to VARCHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '0')";
    }

    /**
     * Presto-specific decimal normalization with configurable decimal places.
     * Use FORMAT to ensure specified decimal places with trailing zeros.
     * This ensures consistent decimal representation across databases.
     */
    @Override
    protected String normalizeDecimal(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("decimal", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("decimal", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNCATE";
            return "COALESCE(TRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS VARCHAR)), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round: apply ROUND then format
            return "COALESCE(FORMAT('%." + precision + "f', ROUND(" + quotedCol + ", " + precision + ")), '" + defaultValue + "')";
        } else {
            // Truncate: apply TRUNCATE then format
            return "COALESCE(FORMAT('%." + precision + "f', TRUNCATE(" + quotedCol + ", " + precision + ")), '" + defaultValue + "')";
        }
    }

    /**
     * Presto-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT/REAL is single-precision and may have precision issues.
     * Cast to DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNCATE";
            return "COALESCE(TRIM(CAST(" + roundFunction + "(CAST(" + quotedCol + " AS DOUBLE), 0) AS VARCHAR)), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round: apply ROUND then format
            return "COALESCE(FORMAT('%." + precision + "f', ROUND(CAST(" + quotedCol + " AS DOUBLE), " + precision + ")), '" + defaultValue + "')";
        } else {
            // Truncate: apply TRUNCATE then format
            return "COALESCE(FORMAT('%." + precision + "f', TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + ")), '" + defaultValue + "')";
        }
    }

    /**
     * Presto-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(" + quotedCol + ", '" + resolvePrestoTemporalFormat("date",
                "yyyy-MM-dd", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * Presto-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(CAST(CONCAT('1970-01-01 ', CAST(" + quotedCol
                + " AS VARCHAR)) AS TIMESTAMP), '" + resolvePrestoTemporalFormat("time",
                "HH:mm:ss", "HH:mm:ss") + "'), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(CAST(CONCAT('1970-01-01 ', CAST(" + quotedCol
                + " AS VARCHAR)) AS TIMESTAMP), '" + resolvePrestoTemporalFormat("time_with_timezone",
                "HH:mm:ss", "HH:mm:ss") + "'), '')";
    }

    /**
     * Presto-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * For TIMESTAMP type (no timezone information):
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(" + quotedCol + ", '" + resolvePrestoTemporalFormat("datetime",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * Presto-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMP type:
     * - Convert to UTC timezone before formatting
     * - This matches MySQL and PostgreSQL behavior
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(AT_TIMEZONE(" + quotedCol + ", '"
                + resolvePrestoTimezone("timestamp", "UTC") + "'), '" + resolvePrestoTemporalFormat("timestamp",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * Presto-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(AT_TIMEZONE(" + quotedCol + ", '"
                + resolvePrestoTimezone("timestamp_with_timezone", "UTC") + "'), '" + resolvePrestoTemporalFormat("timestamp_with_timezone",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    private String resolvePrestoTimezone(String dataTypeName, String defaultTimezone) {
        return escapeSqlLiteral(getTimezone(dataTypeName, defaultTimezone));
    }

    private String resolvePrestoTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("Presto", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                prestoTemporalTokens(dataTypeName));
    }

    private Set<String> prestoTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("yyyy", "yy", "MMMM", "MMM", "MM", "M", "dd", "d");
        Set<String> timeTokens = temporalTokens("HH", "H", "hh", "h", "mm", "m", "ss", "s", "SSS", "a");
        if ("date".equals(dataTypeName)) {
            return dateTokens;
        }
        if ("time".equals(dataTypeName) || "time_with_timezone".equals(dataTypeName)) {
            return timeTokens;
        }
        dateTokens.addAll(timeTokens);
        return dateTokens;
    }

    /**
     * Presto-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = TRUE THEN '1' ELSE '0' END";
    }

    /**
     * Presto-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(UPPER(TO_HEX(" + quotedCol + ")), '')";
    }

    /**
     * Presto-specific JSON normalization: convert to VARCHAR.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Presto-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '0')";
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
            case "long":
                return DataType.BIGINT;
            case "decimal":
            case "numeric":
                return DataType.DECIMAL;
            case "real":
            case "float":
                return DataType.FLOAT;
            case "double":
                return DataType.DOUBLE;
            case "varchar":
            case "string":
                return DataType.VARCHAR;
            case "char":
                return DataType.CHAR;
            case "text":
                return DataType.TEXT;
            case "date":
                return DataType.DATE;
            case "time":
                return DataType.TIME;
            case "timestamp":
            case "datetime":
                return DataType.TIMESTAMP;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "json":
                return DataType.JSON;
            case "varbinary":
            case "binary":
                return DataType.BLOB;
            case "array":
            case "map":
            case "row":
                return DataType.TEXT;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "VARCHAR";
            case TINYINT:
                return "TINYINT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case FLOAT:
                return "REAL";
            case DOUBLE:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "TIMESTAMP";
            case BOOLEAN:
                return "BOOLEAN";
            case JSON:
                return "JSON";
            case BLOB:
                return "VARBINARY";
            default:
                return super.formatDataType(dataType, length, precision, scale);
        }
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null)
            return "NULL";
        return "TIMESTAMP '" + timestamp + "'";
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value;
    }
}
