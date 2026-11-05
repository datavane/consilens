package com.consilens.connector.trino;

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
 * Trino data type handler.
 */
public class TrinoDataTypeHandler extends BaseDataTypeHandler {

    public TrinoDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new Trino data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public TrinoDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /** 科学计数渲染：Trino format()（Java Formatter，与本地路径同源）。 */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        return "format('%.6e', " + quotedCol + ")";
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
            case "INTEGER":
            case "INT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
            case "BIGINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).build();
            case "REAL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "DOUBLE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "DECIMAL": {
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
            case "UUID":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.UUID_TYPE).originType(originType).build();
            default:
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
     * Trino-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), " + stringNullReplacement() + ")";
    }

    /**
     * Trino-specific integer normalization: CAST to VARCHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
    }

    /**
     * Trino-specific decimal normalization with configurable decimal places.
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
            return "COALESCE(TRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS VARCHAR)), '')";
        }


        // Trino 的 FORMAT('%.4f', NULL) 返回字符串 'null'（不是 SQL NULL），COALESCE 不会兜底，
        // 归一化结果会变成字面量 "null"，与其它方言的 NULL → '' 不一致（全 NULL 行误报）。
        // 必须先判空再格式化。
        if (rounding) {
            // Round: apply ROUND then format
            return "CASE WHEN " + quotedCol + " IS NULL THEN ''"
                    + " ELSE FORMAT('%." + precision + "f', ROUND(" + quotedCol + ", " + precision + ")) END";
        } else {
            // Truncate: apply TRUNCATE then format
            return "CASE WHEN " + quotedCol + " IS NULL THEN ''"
                    + " ELSE FORMAT('%." + precision + "f', TRUNCATE(" + quotedCol + ", " + precision + ")) END";
        }
    }

    /**
     * Trino-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT/REAL is single-precision and may have precision issues.
     * Cast to DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 6
        int precision = getPrecision("float", 6);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNCATE";
            return "COALESCE(TRIM(CAST(" + roundFunction + "(CAST(" + quotedCol + " AS DOUBLE), 0) AS VARCHAR)), '')";
        }


        // 同 normalizeDecimal：FORMAT 遇 NULL 会产出字面量 'null'，必须先判空。
        if (rounding) {
            // Round: apply ROUND then format
            return "CASE WHEN " + quotedCol + " IS NULL THEN ''"
                    + " ELSE FORMAT('%." + precision + "f', ROUND(CAST(" + quotedCol + " AS DOUBLE), "
                    + precision + ")) END";
        } else {
            // Truncate: apply TRUNCATE then format
            return "CASE WHEN " + quotedCol + " IS NULL THEN ''"
                    + " ELSE FORMAT('%." + precision + "f', TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), "
                    + precision + ")) END";
        }
    }

    /**
     * Trino-specific date normalization.
     * NOTE: Returns original column to avoid type mismatch in WHERE clauses.
     * Formatting is applied in the query generator's checksum SQL.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        // 不能再返回原始列：本地比对路径（normalizeColumn）会把 NULL 原样带出，
        // 与其它方言 NULL → '' 不一致（全 NULL 行误报 c_date/c_time）。
        return formatDateForChecksum(quotedCol);
    }

    /**
     * Trino-specific time normalization.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        // Trino 的 FORMAT_DATETIME 不接受 TIME 输入，CAST 渲染即 'HH:mm:ss'；
        // 委托 checksum 分支，保证 join/local 与 checksum 构造上同形。
        return formatDateTimeForChecksum(quotedCol, "time");
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return formatDateTimeForChecksum(quotedCol, "time_with_timezone");
    }

    /**
     * Trino-specific datetime normalization.
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(FORMAT_DATETIME(" + quotedCol + ", '" + resolveTrinoTemporalFormat("datetime",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * Trino-specific timestamp normalization.
     * NOTE: Returns original column to avoid type mismatch in WHERE clauses.
     * Formatting is applied in the query generator's checksum SQL.
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // Must match formatDateTimeForChecksum exactly: FORMAT_DATETIME on a
        // TIMESTAMP WITH TIME ZONE appends " UTC" to the rendered string, which
        // desynchronizes the local-compare path (normalizeColumn) from the
        // checksum path and from other dialects' plain wall-clock output.
        return "COALESCE(FORMAT_DATETIME(AT_TIMEZONE(" + quotedCol + ", '"
                + resolveTrinoTimezone("timestamp", "UTC") + "'), 'yyyy-MM-dd HH:mm:ss'), '')";
    }

    /**
     * Trino-specific timestamp with timezone normalization.
     * NOTE: Returns original column to avoid type mismatch in WHERE clauses.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Returning the raw column makes getString() render "… HH:mm:ss UTC"
        // (Trino's varchar cast of timestamptz), which the Java-side normalizer
        // cannot parse and passes through verbatim — desynchronizing the
        // local-compare path from the checksum path and from other dialects.
        // Render the canonical UTC wall-clock instead (same as checksum SQL).
        return "COALESCE(FORMAT_DATETIME(AT_TIMEZONE(" + quotedCol + ", '"
                + resolveTrinoTimezone("timestamp_with_timezone", "UTC") + "'), 'yyyy-MM-dd HH:mm:ss'), '')";
    }

    /**
     * Returns the formatted SQL expression for date columns in checksum calculation.
     * Called by TrinoSqlQueryGenerator to format Date columns for CONCAT().
     */
    public String formatDateForChecksum(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
    }

    /**
     * Returns the formatted SQL expression for datetime/timestamp columns in checksum calculation.
     * Applies timezone conversion and formatting.
     */
    public String formatDateTimeForChecksum(String quotedCol, String dataType) {
        if ("timestamp".equals(dataType) || "timestamp_with_timezone".equals(dataType)) {
            return "COALESCE(FORMAT_DATETIME(AT_TIMEZONE(" + quotedCol + ", '"
                    + resolveTrinoTimezone(dataType, "UTC") + "'), '" + resolveTrinoTemporalFormat(dataType,
                    "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
        }
        if ("time".equals(dataType) || "time_with_timezone".equals(dataType)) {
            // TIME columns carry no date part and Trino's FORMAT_DATETIME rejects
            // TIME inputs; render via CAST (e.g. '12:34:56').
            return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
        }
        return "COALESCE(FORMAT_DATETIME(" + quotedCol + ", '" + resolveTrinoTemporalFormat(dataType,
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    private String resolveTrinoTimezone(String dataTypeName, String defaultTimezone) {
        return escapeSqlLiteral(getTimezone(dataTypeName, defaultTimezone));
    }

    private String resolveTrinoTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("Trino", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                trinoTemporalTokens(dataTypeName));
    }

    private Set<String> trinoTemporalTokens(String dataTypeName) {
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
     * Trino-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = TRUE THEN '1' ELSE '0' END";
    }

    /**
     * Trino-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(UPPER(TO_HEX(" + quotedCol + ")), '')";
    }

    /**
     * Trino-specific JSON normalization: json_format is the only valid
     * json-to-varchar rendering (CAST(json AS VARCHAR) throws
     * INVALID_CAST_ARGUMENT).
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(json_format(" + quotedCol + "), '')";
    }

    /**
     * Trino-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
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
                // Complex types map to TEXT for compatibility
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
                return "VARCHAR"; // Trino uses VARCHAR for text
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
                return "REAL"; // Trino uses REAL for single-precision
            case DOUBLE:
                return "DOUBLE";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "TIMESTAMP"; // Default precision is 3 (milliseconds)
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
