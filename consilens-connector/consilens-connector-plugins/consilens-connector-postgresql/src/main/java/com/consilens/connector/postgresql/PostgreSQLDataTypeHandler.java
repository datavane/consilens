package com.consilens.connector.postgresql;

import com.consilens.common.type.StructField;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL data type handler.
 * 
 * <p>
 * Provides PostgreSQL-specific data type normalization and conversion:
 * <ul>
 * <li>Column normalization for checksum calculation</li>
 * <li>Data type mapping from source to PostgreSQL types</li>
 * <li>Timestamp formatting for PostgreSQL</li>
 * </ul>
 * 
 * @since 1.0.0
 */
@Slf4j
public class PostgreSQLDataTypeHandler extends BaseDataTypeHandler {

    public PostgreSQLDataTypeHandler(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
    }
    
    public PostgreSQLDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * 科学计数渲染：PostgreSQL {@code to_char(v, '9.999999EEEE')}。
     *
     * <p>注意不能加 {@code FM}（PG 报 “EEEE is incompatible with other formats”）；
     * 实测输出与 Java {@code %.6e} 逐字一致：{@code 1.000000e+300}、{@code -2.500000e-300}、
     * 进位 {@code 9.9999995e10 → 1.000000e+11}。
     */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        return "TO_CHAR(" + quotedCol + ", '9.999999EEEE')";
    }


    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        if (originType == null || originType.isBlank()) {
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE)
                    .originType(originType)
                    .build();
        }

        String upperType = normalizeTypeExpression(originType);
        if (upperType.endsWith("[]")) {
            TypeDescriptor elementType = convertToTypeDescriptor(originType.substring(0, originType.length() - 2));
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE)
                    .originType(originType)
                    .elementType(elementType)
                    .build();
        }

        String baseType = extractBaseType(upperType);
        switch (baseType) {
            case "SMALLINT":
            case "INT2":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(16)
                        .build();
            case "INTEGER":
            case "INT":
            case "INT4":
            case "SERIAL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(32)
                        .build();
            case "BIGINT":
            case "INT8":
            case "BIGSERIAL":
            case "OID":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(64)
                        .build();
            case "REAL":
            case "FLOAT4":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE)
                        .originType(originType)
                        .build();
            case "DOUBLE PRECISION":
            case "FLOAT8":
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE)
                        .originType(originType)
                        .build();
            case "NUMERIC":
            case "DECIMAL": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 131072)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 16383)
                        .build();
            }
            case "MONEY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(19)
                        .numericScale(2)
                        .build();
            case "CHAR":
            case "CHARACTER":
            case "BPCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType) != null ? extractLength(upperType) : 1)
                        .build();
            case "VARCHAR":
            case "CHARACTER VARYING":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType))
                        .build();
            case "TEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(true)
                        .build();
            case "BYTEA":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(true)
                        .build();
            case "BIT": {
                // BIT(1) 语义是布尔：按 BINARY 归一化会渲染 HEX（NULL → ''），与其它方言的
                // 布尔/整数归一化（NULL → '0'）不一致，跨源比对全 NULL 行会误报。
                // BIT(n>1) 仍是位串，保持 BINARY。
                Integer bitLength = extractLength(upperType);
                int bits = bitLength != null ? bitLength : 1;
                if (bits <= 1) {
                    return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE)
                            .originType(originType)
                            .build();
                }
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .length(bits)
                        .build();
            }
            case "VARBIT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType))
                        .build();
            case "DATE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE)
                        .originType(originType)
                        .build();
            case "TIME":
            case "TIME WITHOUT TIME ZONE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIME_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .build();
            case "TIMETZ":
            case "TIME WITH TIME ZONE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIME_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .withTimezone(true)
                        .build();
            case "TIMESTAMP":
            case "TIMESTAMP WITHOUT TIME ZONE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .build();
            case "TIMESTAMPTZ":
            case "TIMESTAMP WITH TIME ZONE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .withTimezone(true)
                        .build();
            case "INTERVAL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTERVAL_TYPE)
                        .originType(originType)
                        .build();
            case "BOOLEAN":
            case "BOOL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE)
                        .originType(originType)
                        .build();
            case "JSON":
            case "JSONB":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.JSON_TYPE)
                        .originType(originType)
                        .build();
            case "XML":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.XML_TYPE)
                        .originType(originType)
                        .build();
            case "UUID":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.UUID_TYPE)
                        .originType(originType)
                        .build();
            case "POINT":
            case "LINE":
            case "LSEG":
            case "BOX":
            case "PATH":
            case "POLYGON":
            case "CIRCLE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.GEOMETRY_TYPE)
                        .originType(originType)
                        .build();
            case "INET":
            case "CIDR":
            case "MACADDR":
            case "MACADDR8":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .build();
            case "HSTORE":
            case "JSONPATH":
            case "TSQUERY":
            case "TSVECTOR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.OBJECT_TYPE)
                        .originType(originType)
                        .build();
            default:
                if (upperType.startsWith("ENUM(")) {
                    List<String> enumValues = new ArrayList<>();
                    for (String part : splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')))) {
                        enumValues.add(part.replace("'", "").trim());
                    }
                    return TypeDescriptor.builder(com.consilens.common.enums.DataType.ENUM_TYPE)
                            .originType(originType)
                            .enumValues(enumValues)
                            .build();
                }
                if (upperType.startsWith("RECORD(")) {
                    List<StructField> fields = new ArrayList<>();
                    for (String part : splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')))) {
                        int separator = findTopLevelCharacter(part, ' ');
                        if (separator <= 0) {
                            continue;
                        }
                        String fieldName = part.substring(0, separator).trim().replace("\"", "");
                        String fieldType = part.substring(separator + 1).trim();
                        fields.add(StructField.builder(fieldName, convertToTypeDescriptor(fieldType)).build());
                    }
                    if (!fields.isEmpty()) {
                        return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRUCT_TYPE)
                                .originType(originType)
                                .fields(fields)
                                .build();
                    }
                }
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "TEXT";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }

        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE:
                if (typeDescriptor.getBitWidth() == null || typeDescriptor.getBitWidth() <= 16) {
                    return "SMALLINT";
                }
                if (typeDescriptor.getBitWidth() <= 32) {
                    return "INTEGER";
                }
                return "BIGINT";
            case FLOAT_TYPE:
                return "REAL";
            case DOUBLE_TYPE:
                return "DOUBLE PRECISION";
            case DECIMAL_TYPE:
                if (typeDescriptor.getNumericPrecision() != null && typeDescriptor.getNumericScale() != null) {
                    return "NUMERIC(" + typeDescriptor.getNumericPrecision() + "," + typeDescriptor.getNumericScale() + ")";
                }
                return "NUMERIC";
            case STRING_TYPE:
                if (typeDescriptor.isTextType()) {
                    return "TEXT";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARCHAR(" + typeDescriptor.getLength() + ")";
                }
                return "TEXT";
            case ENUM_TYPE:
                if (typeDescriptor.getEnumValues() != null && !typeDescriptor.getEnumValues().isEmpty()) {
                    StringBuilder builder = new StringBuilder("ENUM(");
                    for (int i = 0; i < typeDescriptor.getEnumValues().size(); i++) {
                        if (i > 0) {
                            builder.append(", ");
                        }
                        builder.append('\'').append(typeDescriptor.getEnumValues().get(i).replace("'", "''")).append('\'');
                    }
                    return builder.append(')').toString();
                }
                return "TEXT";
            case BINARY_TYPE:
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARBIT(" + typeDescriptor.getLength() + ")";
                }
                return typeDescriptor.isBlobType() ? "BYTEA" : "BYTEA";
            case DATE_TYPE:
                return "DATE";
            case TIME_TYPE:
                String timeType = typeDescriptor.isWithTimezone() ? "TIME WITH TIME ZONE" : "TIME";
                return typeDescriptor.getTimePrecision() != null
                        ? timeType + "(" + typeDescriptor.getTimePrecision() + ")"
                        : timeType;
            case TIMESTAMP_TYPE:
                String timestampType = typeDescriptor.isWithTimezone()
                        ? "TIMESTAMP WITH TIME ZONE"
                        : "TIMESTAMP";
                return typeDescriptor.getTimePrecision() != null
                        ? timestampType + "(" + typeDescriptor.getTimePrecision() + ")"
                        : timestampType;
            case INTERVAL_TYPE:
                return "INTERVAL";
            case BOOLEAN_TYPE:
                return "BOOLEAN";
            case JSON_TYPE:
                return "JSONB";
            case XML_TYPE:
                return "XML";
            case UUID_TYPE:
                return "UUID";
            case ARRAY_TYPE:
                return convertToOriginType(typeDescriptor.getElementType()) + "[]";
            case MAP_TYPE:
            case STRUCT_TYPE:
            case OBJECT_TYPE:
                return "JSONB";
            case GEOMETRY_TYPE:
                return "GEOMETRY";
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * PostgreSQL-specific integer normalization: CAST to VARCHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     * Must match MySQL's behavior to ensure consistent hashing.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
    }
    /**
     * PostgreSQL-specific string normalization.
     * For CHAR types, CAST to VARCHAR to remove trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // CAST CHAR to VARCHAR to remove trailing spaces, then TRIM
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), " + stringNullReplacement() + ")";
    }

    @Override
    protected String normalizeJson(String quotedCol) {
        // 规范 JSON 形 = 紧凑渲染（键排序 + 去掉 ": " / ", " 分隔空格），与 MySQL 的
        // normalizeJson 完全一致；`json` 类型保留原文（键序、空格），必须经 jsonb 规范化，
        // 否则 `{"a":1,"b":2}` 与 `{"b":2,"a":1}` 会被判为不同（设计 §2.5：JSON 键序应被抹平）。
        //
        // 已知限制：去空格是文本级变换——字符串值内恰含 ": " / ", " 的内容会被一并改写。
        return "COALESCE(REPLACE(REPLACE(CAST(CAST(" + quotedCol + " AS JSONB) AS VARCHAR), ': ', ':'), ', ', ','), '')";
    }
    /**
     * DECIMAL/DOUBLE 渲染对齐 mysql 端 REPLACE(FORMAT(...,4))：按配置精度固定小数位、
     * 去千分位，并归一化负零。pg 的 DOUBLE 直接 CAST VARCHAR 会得到可变精度
     * （1954.25 vs 1954.2500）导致全行误报。
     *
     * <p>TO_CHAR 直接作用于数值列（numeric/double precision 均有重载），不经
     * {@code CAST(... AS DOUBLE PRECISION)}，避免 numeric 列的精度损失。
     */
    /** 从 {@code 0000} 形态的小数位模式里数出位数。 */
    private static int decimalDigits(String pattern) {
        return pattern == null ? 0 : pattern.replaceAll("[^0]", "").length();
    }

    @Override
    protected String normalizeDecimal(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("decimal", 4);
        // Get rounding from config, default to true (round half up)
        boolean rounding = getRounding("decimal", true);

        String rendered;
        if (precision == 0) {
            // Return integer format without decimal point
            String roundFunction = rounding ? "ROUND" : "TRUNC";
            rendered = "TRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS VARCHAR))";
        } else {
            String formatPattern = decimalFormatMask(precision);
            rendered = rounding
                    ? "TO_CHAR(" + quotedCol + ", '" + formatPattern + "')"
                    : "TO_CHAR(TRUNC(" + quotedCol + ", " + precision + "), '" + formatPattern + "')";
        }
        // TO_CHAR 在极小负数舍入到 0 时保留符号（'-0.0000'），而 mysql FORMAT()
        // 丢弃符号（'0.0000'）；归一化掉负零，保证两侧渲染一致。
        return "COALESCE(REGEXP_REPLACE(REPLACE(" + rendered + ", ',', ''), '^-(0[.]0+)$', '\\1'), '')";
    }

    /**
     * PostgreSQL-specific float normalization.
     * CRITICAL: FLOAT/REAL is single-precision and may have precision issues.
     * Cast to DOUBLE PRECISION first to ensure consistent formatting.
     * Precision is configurable via normalization config.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 6
        int precision = getPrecision("float", 6);
        // Get rounding from config, default to true (round half up)
        boolean rounding = getRounding("float", true);
        
        if (precision == 0) {
            // Return integer format without decimal point
            if (rounding) {
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE PRECISION), 0) AS VARCHAR)), '')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNC(CAST(" + quotedCol + " AS DOUBLE PRECISION), 0) AS VARCHAR)), '')";
            }
        }
        
        String decimalPattern = "0".repeat(precision);
        String formatPattern = decimalFormatMask(decimalDigits(decimalPattern));
        
        if (rounding) {
            return "COALESCE(TO_CHAR(CAST(" + quotedCol + " AS DOUBLE PRECISION), '" + formatPattern + "'), '')";
        } else {
            // PostgreSQL TRUNC + TO_CHAR: truncate then format
            return "COALESCE(TO_CHAR(TRUNC(CAST(" + quotedCol + " AS DOUBLE PRECISION), " + precision + "), '" + formatPattern + "'), '')";
        }
    }

    /**
     * PostgreSQL-specific date normalization: YYYY-MM-DD format.
     * DATE type has no timezone information, so no conversion needed.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", '" + resolvePostgreSqlTemporalFormat("date",
                "YYYY-MM-DD", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * PostgreSQL-specific time normalization: HH24:MI:SS format.
     * 
     * Note: PostgreSQL has two TIME types:
     * - TIME WITHOUT TIME ZONE (TIME): No timezone, format directly
     * - TIME WITH TIME ZONE (TIMETZ): Has timezone, but PostgreSQL docs discourage its use
     * 
     * Current implementation assumes TIME (most common case).
     * If TIMETZ is used and cross-database consistency is needed, consider:
     * TO_CHAR(col AT TIME ZONE 'UTC', 'HH24:MI:SS')
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", '" + resolvePostgreSqlTemporalFormat("time",
                "HH24:MI:SS", "HH24:MI:SS") + "'), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        String expression = quotedCol;
        String targetTimezone = getTimezone("time_with_timezone", null);
        if (targetTimezone != null && !targetTimezone.isBlank()) {
            expression = quotedCol + " AT TIME ZONE '" + escapeSqlLiteral(targetTimezone) + "'";
        }
        return "COALESCE(TO_CHAR(" + expression + ", '" + resolvePostgreSqlTemporalFormat("time_with_timezone",
                "HH24:MI:SS", "HH24:MI:SS") + "'), '')";
    }

    /**
     * PostgreSQL-specific datetime normalization: YYYY-MM-DD HH24:MI:SS format.
     * 
     * For DATETIME type (mapped from MySQL DATETIME or other databases):
     * - Assume the value is stored as-is without timezone interpretation
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        String expression = quotedCol;
        String targetTimezone = getTimezone("datetime", null);
        if (targetTimezone != null && !targetTimezone.isBlank()) {
            expression = quotedCol + " AT TIME ZONE current_setting('TIMEZONE') AT TIME ZONE '"
                    + escapeSqlLiteral(targetTimezone) + "'";
        }
        return "COALESCE(TO_CHAR(" + expression + ", '" + resolvePostgreSqlTemporalFormat("datetime",
                "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * PostgreSQL-specific timestamp normalization: YYYY-MM-DD HH24:MI:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMP type (PostgreSQL TIMESTAMP WITHOUT TIME ZONE):
     * - The value is stored without timezone info
     * - Need to interpret it in the session timezone, then convert to UTC
     * - Use two-step conversion with dynamic timezone from session
     * 
     * Note: Uses current_setting('TIMEZONE') to get the session timezone dynamically.
     * This avoids hardcoding timezone and works with any PostgreSQL configuration.
     * This matches MySQL TIMESTAMP behavior (timezone-aware).
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        String targetTimezone = escapeSqlLiteral(getTimezone("timestamp", "UTC"));
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE current_setting('TIMEZONE') AT TIME ZONE '"
                + targetTimezone + "', '" + resolvePostgreSqlTemporalFormat("timestamp",
                "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * PostgreSQL-specific timestamp with timezone normalization: YYYY-MM-DD HH24:MI:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMPTZ type (PostgreSQL TIMESTAMP WITH TIME ZONE):
     * - The value already has timezone information
     * - Just convert to UTC directly
     * - This matches MySQL TIMESTAMP behavior (timezone-aware)
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        String targetTimezone = escapeSqlLiteral(getTimezone("timestamp_with_timezone", "UTC"));
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE '" + targetTimezone + "', '"
                + resolvePostgreSqlTemporalFormat("timestamp_with_timezone", "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * PostgreSQL-specific blob normalization: convert to hexadecimal.
     * Use UPPER() to ensure uppercase hex output for cross-database consistency with MySQL.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(UPPER(ENCODE(" + quotedCol + ", 'hex')), '')";
    }

    /**
     * PostgreSQL-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        // 用文本形态判定，同时兼容 boolean（文本 'true'/'false'）与 bit(1)（文本 '1'/'0'）：
        // `col = TRUE` 对 bit 列会报 “operator does not exist: bit = boolean”。
        // 对 boolean 列，输出与旧实现完全一致（true→'1'、false→'0'、NULL→'0'）。
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' "
                + "WHEN CAST(" + quotedCol + " AS VARCHAR) IN ('1', 't', 'true', 'TRUE') "
                + "THEN '1' ELSE '0' END";
    }

    private String resolvePostgreSqlTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("PostgreSQL", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                postgreSqlTemporalTokens(dataTypeName));
    }

    private Set<String> postgreSqlTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("YYYY", "YY", "MONTH", "MON", "MM", "DD");
        Set<String> timeTokens = temporalTokens("HH24", "HH12", "HH", "MI", "SS", "MS", "US", "AM", "PM");
        if ("date".equals(dataTypeName)) {
            return dateTokens;
        }
        if ("time".equals(dataTypeName) || "time_with_timezone".equals(dataTypeName)) {
            return timeTokens;
        }
        dateTokens.addAll(timeTokens);
        return dateTokens;
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null) {
            return DataType.UNKNOWN;
        }
        String type = sourceType.toLowerCase();
        
        // Log the type conversion at DEBUG level
        log.debug("PostgreSQLDataTypeHandler.convertToDataType: '{}' -> lowercase '{}'", sourceType, type);
        switch (type) {
            case "varchar":
            case "character varying":
                return DataType.VARCHAR;
            case "char":
            case "character":
                return DataType.CHAR;
            case "text":
                return DataType.TEXT;
            case "int":
            case "integer":
            case "int4":
                return DataType.INTEGER;
            case "smallint":
            case "int2":
                return DataType.SMALLINT;
            case "bigint":
            case "int8":
                return DataType.BIGINT;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "date":
                return DataType.DATE;
            case "timestamp":
                return DataType.TIMESTAMP;
            case "timestamptz":
                return DataType.TIMESTAMP_WITH_TIMEZONE;
            case "time":
                return DataType.TIME;
            case "json":
                return DataType.JSON;
            case "jsonb":
                return DataType.JSONB;
            case "uuid":
                return DataType.UUID;
            case "bytea":
                return DataType.BLOB;
            default:
                // Handle types with precision/scale like "decimal(10,2)", "numeric(10,2)", "float4", etc.
                if (type.startsWith("decimal") || type.startsWith("numeric")) {
                    return DataType.DECIMAL;
                } else if (type.startsWith("real") || type.equals("float4")) {
                    return DataType.REAL;
                } else if (type.startsWith("double") || type.equals("double precision") || 
                           type.equals("float8")) {
                    return DataType.DOUBLE;
                }
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision,
            int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "TEXT";
            case INTEGER:
                return "INTEGER";
            case SMALLINT:
                return "SMALLINT";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case BOOLEAN:
                return "BOOLEAN";
            case DATE:
                return "DATE";
            case TIMESTAMP:
                return precision > 0 ? "TIMESTAMP(" + precision + ")" : "TIMESTAMP";
            case TIMESTAMP_WITH_TIMEZONE:
                return precision > 0 ? "TIMESTAMPTZ(" + precision + ")" : "TIMESTAMPTZ";
            case TIME:
                return "TIME";
            case JSON:
                return "JSON";
            case JSONB:
                return "JSONB";
            case UUID:
                return "UUID";
            case BLOB:
                return "BYTEA";
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
