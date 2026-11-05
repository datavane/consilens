package com.consilens.connector.oceanbase;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

/**
 * OceanBase data type handler.
 *
 * <p>
 * Provides OceanBase-specific data type normalization and conversion.
 * OceanBase in MySQL mode is highly compatible with MySQL, so this handler
 * shares much logic with MySQLDataTypeHandler.
 * </p>
 *
 * <p>
 * Key differences from MySQL:
 * <ul>
 * <li>OceanBase supports additional data types (e.g., LARGEINT)</li>
 * <li>Decimal precision handling may differ slightly</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class OceanBaseDataTypeHandler extends BaseDataTypeHandler {

    public OceanBaseDataTypeHandler(CapabilityProvider capabilityProvider) {
        super(capabilityProvider);
    }

    public OceanBaseDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
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
            case "LARGEINT":
                // OceanBase specific: LARGEINT is 128-bit integer
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE)
                        .originType(originType)
                        .bitWidth(128)
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
            case "BIT":
                // BIT(1) 语义是布尔：按 BINARY 归一化会渲染 HEX，与其它方言的布尔/整数
                // 归一化（NULL → '0'）不一致，跨源比对全 NULL 行会误报。
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE)
                        .originType(originType)
                        .length(extractLength(upperType) != null ? extractLength(upperType) : 1)
                        .build();
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
                if (bitWidth <= 64) {
                    return "BIGINT" + suffix;
                }
                // OceanBase specific: LARGEINT for 128-bit
                return "LARGEINT" + suffix;
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
     * OceanBase-specific integer normalization: CAST to CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '')";
    }

    /**
     * OceanBase-specific string normalization.
     * Use TRIM directly without CAST to avoid truncation issues.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), " + stringNullReplacement() + ")";
    }

    /**
     * OceanBase-specific decimal normalization.
     * Use FORMAT to ensure decimal places with trailing zeros, then remove commas.
     * Precision is configurable via normalization config.
     */
    @Override
    protected String normalizeDecimal(String quotedCol) {
        int precision = getPrecision("decimal", 4);
        boolean rounding = getRounding("decimal", true);

        if (precision == 0) {
            if (rounding) {
                return "COALESCE(TRIM(CAST(ROUND(" + quotedCol + ", 0) AS CHAR)), '')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(" + quotedCol + ", 0) AS CHAR)), '')";
            }
        }

        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(" + quotedCol + ", " + precision + "), ',', ''), '')";
        } else {
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(" + quotedCol + ", " + precision + "), " + precision + "), ',', ''), '')";
        }
    }

    /**
     * OceanBase-specific float normalization.
     * CRITICAL: FLOAT is single-precision and may have precision issues with FORMAT().
     * Cast to DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        int precision = getPrecision("float", 6);
        boolean rounding = getRounding("float", true);

        if (precision == 0) {
            if (rounding) {
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '')";
            }
        }

        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), ',', ''), '')";
        } else {
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), " + precision + "), ',', ''), '')";
        }
    }

    /**
     * OceanBase-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '" + resolveOceanBaseTemporalFormat("date",
                "%Y-%m-%d", "%Y-%m-%d") + "'), '')";
    }

    /**
     * OceanBase-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '" + resolveOceanBaseTemporalFormat("time",
                "%H:%i:%s", "%H:%i:%s") + "'), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '" + resolveOceanBaseTemporalFormat("time_with_timezone",
                "%H:%i:%s", "%H:%i:%s") + "'), '')";
    }

    /**
     * OceanBase-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        String targetTimezone = resolveOceanBaseTimezone("datetime", "+00:00");
        String format = resolveOceanBaseTemporalFormat("datetime", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", \'" + format + "'), '')";
    }

    /**
     * OceanBase-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        String targetTimezone = resolveOceanBaseTimezone("timestamp", "+00:00");
        String format = resolveOceanBaseTemporalFormat("timestamp", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", \'" + format + "'), '')";
    }

    /**
     * OceanBase-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        String targetTimezone = resolveOceanBaseTimezone("timestamp_with_timezone", "+00:00");
        String format = resolveOceanBaseTemporalFormat("timestamp_with_timezone", "%Y-%m-%d %H:%i:%s", "%Y-%m-%d");
        return "COALESCE(DATE_FORMAT(" + tzShiftByAnchor(quotedCol, targetTimezone) + ", \'" + format + "'), '')";
    }

    /**
     * OceanBase-specific boolean normalization: '0' or '1'.
     * OceanBase BOOLEAN is actually TINYINT(1), so we need to handle it explicitly.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * OceanBase-specific blob normalization: convert to hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(HEX(" + quotedCol + "), '')";
    }

    /**
     * OceanBase-specific JSON normalization: convert to CHAR.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS CHAR), '')";
    }

    /**
     * OceanBase-specific default normalization: CAST to CHAR instead of VARCHAR.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '')";
    }

    private String resolveOceanBaseTimezone(String dataTypeName, String defaultTimezone) {
        String timezone = getTimezone(dataTypeName, defaultTimezone);
        if ("UTC".equalsIgnoreCase(timezone)) {
            return "+00:00";
        }
        return escapeSqlLiteral(timezone);
    }

    private String resolveOceanBaseTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("OceanBase", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                oceanBaseTemporalTokens(dataTypeName));
    }

    private Set<String> oceanBaseTemporalTokens(String dataTypeName) {
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

        // Handle UNSIGNED variants by stripping the "unsigned" keyword
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
                return DataType.INTEGER;
            case "bigint":
                return DataType.BIGINT;
            case "largeint":
                // OceanBase specific: LARGEINT maps to BIGINT as closest standard
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
