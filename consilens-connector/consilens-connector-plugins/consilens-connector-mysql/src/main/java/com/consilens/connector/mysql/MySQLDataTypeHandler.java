package com.consilens.connector.mysql;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

/**
 * MySQL data type handler.
 * 
 * <p>
 * Provides MySQL-specific data type normalization and conversion:
 * <ul>
 * <li>Column normalization for checksum calculation</li>
 * <li>Data type mapping from source to MySQL types</li>
 * <li>Timestamp formatting for MySQL</li>
 * </ul>
 * 
 * @since 1.0.0
 */
@Slf4j
public class MySQLDataTypeHandler extends BaseDataTypeHandler {

    public MySQLDataTypeHandler(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
    }
    
    public MySQLDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * 科学计数渲染：转发到 {@link BaseDataTypeHandler#mysqlFamilyScientificFloatExpression(String)}
     * 的共享实现——mysql 族各方言必须逐字同形，不能各自拷贝。
     */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        return mysqlFamilyScientificFloatExpression(quotedCol);
    }

    /**
     * MySQL 的 YEAR 逻辑上是 16 位整数，但 JDBC 驱动把它读成 {@code java.sql.Date}
     * （如 2092-01-01），而 PostgreSQL 的等价列读成整数。按逻辑类型归一化为年份整数，
     * 让跨库本地比对不依赖具体驱动的返回形态。
     */
    @Override
    public Object normalizeValueForComparison(Object value, DataType dataType) {
        if (value instanceof java.sql.Date && dataType != null && dataType.isNumeric()) {
            return ((java.sql.Date) value).toLocalDate().getYear();
        }
        return value;
    }



    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        if (originType == null || originType.isBlank()) {
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE)
                    .originType(originType)
                    .build();
        }

        String upperType = normalizeTypeExpression(originType);
        String baseType = extractBaseType(upperType);
        boolean unsigned = upperType.contains("UNSIGNED");

        switch (baseType) {
            case "TINYINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(8)
                        .unsigned(unsigned)
                        .build();
            case "SMALLINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(16)
                        .unsigned(unsigned)
                        .build();
            case "MEDIUMINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(24)
                        .unsigned(unsigned)
                        .build();
            case "INT":
            case "INTEGER":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(32)
                        .unsigned(unsigned)
                        .build();
            case "BIGINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(64)
                        .unsigned(unsigned)
                        .build();
            case "YEAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(16)
                        .build();
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE)
                        .originType(originType)
                        .build();
            case "DOUBLE":
            case "DOUBLE PRECISION":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE)
                        .originType(originType)
                        .build();
            case "DECIMAL":
            case "NUMERIC":
            case "DEC":
            case "FIXED": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 10)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 0)
                        .build();
            }
            case "BIT": {
                // BIT(1) 语义是布尔：若按 BINARY 归一化会被渲染成 HEX，与其他方言对同一逻辑列的
                // 整数/布尔归一化（'1'/'0'）不一致——跨源比对时 NULL 行一侧渲染 ''、另一侧 '0'
                // （实测 dq_cmp_alltype pk=2 全 NULL 行在 mysql↔starrocks 误报 c_bit/c_boolean）。
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
            case "CHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType) != null ? extractLength(upperType) : 1)
                        .build();
            case "VARCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType))
                        .build();
            case "TINYTEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(true)
                        .length(255)
                        .build();
            case "TEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(true)
                        .length(65535)
                        .build();
            case "MEDIUMTEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(true)
                        .length(16777215)
                        .build();
            case "LONGTEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(true)
                        .build();
            case "ENUM":
            case "SET":
                // ENUM/SET 的取值是标签字符串，必须按字符串归一化：落到默认分支会渲染成
                // COALESCE(CAST(col AS CHAR), '0')，与其他方言的 VARCHAR（NULL → ''）不一致，
                // 跨源比对时 NULL 行会误报（实测 dq_cmp_alltype pk=2 的 c_enum）。
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .build();
            case "BINARY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType) != null ? extractLength(upperType) : 1)
                        .build();
            case "VARBINARY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType))
                        .build();
            case "TINYBLOB":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(true)
                        .length(255)
                        .build();
            case "BLOB":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(true)
                        .length(65535)
                        .build();
            case "MEDIUMBLOB":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(true)
                        .length(16777215)
                        .build();
            case "LONGBLOB":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(true)
                        .build();
            case "DATE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE)
                        .originType(originType)
                        .build();
            case "TIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIME_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .build();
            case "DATETIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .build();
            case "TIMESTAMP":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .withTimezone(true)
                        .build();
            case "BOOLEAN":
            case "BOOL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE)
                        .originType(originType)
                        .build();
            case "JSON":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.JSON_TYPE)
                        .originType(originType)
                        .build();
            case "GEOMETRY":
            case "POINT":
            case "LINESTRING":
            case "POLYGON":
            case "MULTIPOINT":
            case "MULTILINESTRING":
            case "MULTIPOLYGON":
            case "GEOMETRYCOLLECTION":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.GEOMETRY_TYPE)
                        .originType(originType)
                        .build();
            default:
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
            case INTEGER_TYPE: {
                int bitWidth = typeDescriptor.getBitWidth() != null ? typeDescriptor.getBitWidth() : 32;
                String suffix = typeDescriptor.isUnsigned() ? " UNSIGNED" : "";
                if (bitWidth <= 8) {
                    return "TINYINT" + suffix;
                }
                if (bitWidth <= 16) {
                    return "SMALLINT" + suffix;
                }
                if (bitWidth <= 24) {
                    return "MEDIUMINT" + suffix;
                }
                if (bitWidth <= 32) {
                    return "INT" + suffix;
                }
                return "BIGINT" + suffix;
            }
            case FLOAT_TYPE:
                return "FLOAT";
            case DOUBLE_TYPE:
                return "DOUBLE";
            case DECIMAL_TYPE: {
                int precision = typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 10;
                int scale = typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0;
                return "DECIMAL(" + precision + "," + scale + ")";
            }
            case ENUM_TYPE:
                return "VARCHAR(255)";
            case STRING_TYPE:
                if (typeDescriptor.isTextType()) {
                    Integer length = typeDescriptor.getLength();
                    if (length != null && length <= 255) {
                        return "TINYTEXT";
                    }
                    if (length != null && length <= 65535) {
                        return "TEXT";
                    }
                    if (length != null && length <= 16777215) {
                        return "MEDIUMTEXT";
                    }
                    return "LONGTEXT";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARCHAR(" + typeDescriptor.getLength() + ")";
                }
                return "TEXT";
            case BINARY_TYPE:
                if (typeDescriptor.isBlobType()) {
                    Integer length = typeDescriptor.getLength();
                    if (length != null && length <= 255) {
                        return "TINYBLOB";
                    }
                    if (length != null && length <= 65535) {
                        return "BLOB";
                    }
                    if (length != null && length <= 16777215) {
                        return "MEDIUMBLOB";
                    }
                    return "LONGBLOB";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARBINARY(" + typeDescriptor.getLength() + ")";
                }
                return "BLOB";
            case DATE_TYPE:
                return "DATE";
            case TIME_TYPE:
                return typeDescriptor.getTimePrecision() != null
                        ? "TIME(" + typeDescriptor.getTimePrecision() + ")"
                        : "TIME";
            case TIMESTAMP_TYPE:
                String temporalType = typeDescriptor.isWithTimezone() ? "TIMESTAMP" : "DATETIME";
                return typeDescriptor.getTimePrecision() != null
                        ? temporalType + "(" + typeDescriptor.getTimePrecision() + ")"
                        : temporalType;
            case BOOLEAN_TYPE:
                return "TINYINT(1)";
            case JSON_TYPE:
                return "JSON";
            case GEOMETRY_TYPE:
                return "GEOMETRY";
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * MySQL-specific integer normalization: CAST to CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '')";
    }
    /**
     * MySQL-specific string normalization.
     * Use TRIM directly without CAST to avoid truncation issues.
     * MySQL's CAST(x AS CHAR) defaults to CHAR(1) which truncates to first character.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // Just TRIM, no CAST needed for MySQL
        return "COALESCE(TRIM(" + quotedCol + "), " + stringNullReplacement() + ")";
    }

    /**
     * MySQL-specific decimal normalization.
     * Use FORMAT to ensure decimal places with trailing zeros, then remove commas.
     * This ensures consistent decimal representation across databases.
     * Precision is configurable via normalization config.
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
                return "COALESCE(TRIM(CAST(ROUND(" + quotedCol + ", 0) AS CHAR)), '')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(" + quotedCol + ", 0) AS CHAR)), '')";
            }
        }
        
        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(" + quotedCol + ", " + precision + "), ',', ''), '')";
        } else {
            // MySQL TRUNCATE + FORMAT: truncate then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(" + quotedCol + ", " + precision + "), " + precision + "), ',', ''), '')";
        }
    }

    /**
     * MySQL-specific float normalization.
     * CRITICAL: FLOAT is single-precision and may have precision issues with FORMAT().
     * Cast to DOUBLE first to ensure consistent formatting.
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
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '')";
            }
        }
        
        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), ',', ''), '')";
        } else {
            // MySQL TRUNCATE + FORMAT: truncate then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), " + precision + "), ',', ''), '')";
        }
    }

    /**
     * MySQL-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '" + resolveMySqlTemporalFormat("date",
                "%Y-%m-%d", "%Y-%m-%d") + "'), '')";
    }

    /**
     * MySQL-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '" + resolveMySqlTemporalFormat("time",
                "%H:%i:%s", "%H:%i:%s") + "'), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '" + resolveMySqlTemporalFormat("time_with_timezone",
                "%H:%i:%s", "%H:%i:%s") + "'), '')";
    }

    /**
     * MySQL-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     *
     * For DATETIME type:
     * - MySQL DATETIME stores literal values without timezone information
     * - But when comparing with PostgreSQL TIMESTAMPTZ, we need to convert to UTC
     * - Assume the DATETIME value is in the session timezone
     *
     * Note: This matches PostgreSQL TIMESTAMPTZ behavior (timezone-aware).
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        String targetTimezone = resolveMySqlTimezone("datetime", "+00:00");
        String format = resolveMySqlTemporalFormat("datetime", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", '" + format + "'), '')";
    }

    /**
     * MySQL-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * Uses CONVERT_TZ to convert from connection timezone to UTC.
     * 
     * Note: MySQL TIMESTAMP is similar to DATETIME but with automatic timezone conversion.
     * Both need the same UTC conversion for cross-database consistency.
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        String targetTimezone = resolveMySqlTimezone("timestamp", "+00:00");
        String format = resolveMySqlTemporalFormat("timestamp", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", '" + format + "'), '')";
    }

    /**
     * MySQL-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * Note: MySQL doesn't have a native TIMESTAMP WITH TIME ZONE type.
     * This method is provided for compatibility with PostgreSQL TIMESTAMPTZ.
     * MySQL TIMESTAMP is already timezone-aware and should use normalizeTimestamp().
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        String targetTimezone = resolveMySqlTimezone("timestamp_with_timezone", "+00:00");
        String format = resolveMySqlTemporalFormat("timestamp_with_timezone", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", '" + format + "'), '')";
    }

    /**
     * MySQL-specific boolean normalization: '0' or '1'.
     * MySQL BOOLEAN is actually TINYINT(1), so we need to handle it explicitly.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * MySQL-specific blob normalization: convert to hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(HEX(" + quotedCol + "), '')";
    }

    /**
     * MySQL-specific JSON normalization: convert to CHAR.
     * MySQL doesn't support CAST(col AS VARCHAR), must use CHAR.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        // Canonical JSON = compact rendering (strip the ": " and ", "
        // separators that some engines add). Every dialect applies the identical
        // transform, so semantically equal JSON hashes equal across engines;
        // engines with natively compact output (trino/presto json_format) are
        // already in this form.
        //
        // 已知限制：这是文本级变换——字符串值内恰含 ": " / ", " 的内容会被一并改写；
        // 键序也不由本表达式抹平，而是依赖 MySQL 二进制 JSON 自身的键序规范化
        // （结构化重渲染的方言见 pg jsonb / ClickHouse toJSONString / SQL Server OPENJSON）。
        return "COALESCE(REPLACE(REPLACE(CAST(" + quotedCol + " AS CHAR), ': ', ':'), ', ', ','), '')";
    }

    /**
     * MySQL-specific default normalization: CAST to CHAR instead of VARCHAR.
     * This handles BOOLEAN, ENUM, SET and other types.
     * BUGFIX: Use '0' as default value to match PostgreSQL for numeric types like UNSIGNED INT
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '')";
    }

    private String resolveMySqlTimezone(String dataTypeName, String defaultTimezone) {
        String timezone = getTimezone(dataTypeName, defaultTimezone);
        if ("UTC".equalsIgnoreCase(timezone)) {
            return "+00:00";
        }
        return escapeSqlLiteral(timezone);
    }

    private String resolveMySqlTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("MySQL", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                mySqlTemporalTokens(dataTypeName));
    }

    private Set<String> mySqlTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("%Y", "%y", "%m", "%c", "%d", "%e");
        Set<String> timeTokens = temporalTokens("%H", "%h", "%I", "%i", "%s", "%S", "%f", "%p", "%r", "%T");
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
        log.debug("MySQLDataTypeHandler.convertToDataType: '{}' -> lowercase '{}'", sourceType, type);
        
        // Handle UNSIGNED variants by stripping the "unsigned" keyword
        // e.g., "int unsigned" -> "int", "bigint unsigned" -> "bigint"
        if (type.contains("unsigned")) {
            type = type.replace("unsigned", "").trim();
        }
        
        switch (type) {
            case "varchar":
                return DataType.VARCHAR;
            case "char":
                return DataType.CHAR;
            case "text":
            case "mediumtext":
            case "longtext":
            case "tinytext":
                return DataType.TEXT;
            case "int":
            case "integer":
                return DataType.INTEGER;
            case "tinyint":
                return DataType.TINYINT;
            case "smallint":
                return DataType.SMALLINT;
            case "mediumint":
                return DataType.INTEGER; // Map to INTEGER as closest standard
            case "bigint":
                return DataType.BIGINT;
            case "datetime":
                return DataType.DATETIME;
            case "timestamp":
                return DataType.TIMESTAMP;
            case "date":
                return DataType.DATE;
            case "time":
                return DataType.TIME;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "json":
                return DataType.JSON;
            default:
                // Handle types with precision/scale like "decimal(10,2)", "float(7,4)", etc.
                if (type.startsWith("decimal") || type.startsWith("numeric") || 
                    type.startsWith("dec") || type.startsWith("fixed")) {
                    return DataType.DECIMAL;
                } else if (type.startsWith("float")) {
                    return DataType.FLOAT;
                } else if (type.startsWith("double") || type.equals("double precision") || 
                           type.startsWith("real")) {
                    return DataType.DOUBLE;
                } else if (type.startsWith("blob") || type.startsWith("mediumblob") || 
                           type.startsWith("longblob") || type.startsWith("tinyblob")) {
                    return DataType.BLOB;
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
                return "INT";
            case BOOLEAN:
                return "TINYINT(1)";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case BLOB:
                return "BLOB";
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

}
