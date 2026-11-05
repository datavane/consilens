package com.consilens.connector.sqlserver;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.Map;
import java.util.Set;

/**
 * SQL Server data type handler.
 */
public class SQLServerDataTypeHandler extends BaseDataTypeHandler {

    public SQLServerDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new SQL Server data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public SQLServerDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /** 科学计数渲染：SQL Server FORMAT 自定义格式 '0.000000E+00'（.NET 指数至少两位）。 */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        return "LOWER(FORMAT(" + quotedCol + ", '0.000000E+00'))";
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
        switch (baseType) {
            case "TINYINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(8).unsigned(true).build();
            case "SMALLINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).build();
            case "INT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
            case "BIGINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).build();
            case "REAL":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "DECIMAL":
            case "NUMERIC": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 18)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 0)
                        .build();
            }
            case "MONEY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE).originType(originType).numericPrecision(19).numericScale(4).build();
            case "SMALLMONEY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE).originType(originType).numericPrecision(10).numericScale(4).build();
            case "BIT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE).originType(originType).build();
            case "CHAR":
            case "NCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType) != null ? extractLength(upperType) : 1).build();
            case "VARCHAR":
            case "NVARCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE)
                        .originType(originType)
                        .textType(containsMaxLength(upperType))
                        .length(containsMaxLength(upperType) ? null : extractLength(upperType))
                        .build();
            case "TEXT":
            case "NTEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).textType(true).build();
            case "BINARY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).length(extractLength(upperType) != null ? extractLength(upperType) : 1).build();
            case "VARBINARY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE)
                        .originType(originType)
                        .blobType(containsMaxLength(upperType))
                        .length(containsMaxLength(upperType) ? null : extractLength(upperType))
                        .build();
            case "IMAGE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).blobType(true).build();
            case "DATE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE).originType(originType).build();
            case "TIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIME_TYPE).originType(originType).timePrecision(extractLength(upperType)).build();
            case "DATETIME":
            case "SMALLDATETIME":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).build();
            case "DATETIME2":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).timePrecision(extractLength(upperType)).build();
            case "DATETIMEOFFSET":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType).timePrecision(extractLength(upperType)).withTimezone(true).build();
            case "UNIQUEIDENTIFIER":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.UUID_TYPE).originType(originType).build();
            case "XML":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.XML_TYPE).originType(originType).build();
            case "GEOMETRY":
            case "GEOGRAPHY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.GEOMETRY_TYPE).originType(originType).build();
            case "SQL_VARIANT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.OBJECT_TYPE).originType(originType).build();
            default:
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "NVARCHAR(MAX)";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE:
                if (typeDescriptor.isUnsigned() && typeDescriptor.getBitWidth() != null && typeDescriptor.getBitWidth() <= 8) {
                    return "TINYINT";
                }
                if (typeDescriptor.getBitWidth() == null || typeDescriptor.getBitWidth() <= 16) return "SMALLINT";
                if (typeDescriptor.getBitWidth() <= 32) return "INT";
                return "BIGINT";
            case FLOAT_TYPE:
                return "REAL";
            case DOUBLE_TYPE:
                return "FLOAT";
            case DECIMAL_TYPE:
                return "DECIMAL(" + (typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 18)
                        + "," + (typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0) + ")";
            case BOOLEAN_TYPE:
                return "BIT";
            case STRING_TYPE:
                if (typeDescriptor.isTextType()) {
                    return "NVARCHAR(MAX)";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "NVARCHAR(" + typeDescriptor.getLength() + ")";
                }
                return "NVARCHAR(MAX)";
            case BINARY_TYPE:
                if (typeDescriptor.isBlobType()) {
                    return "VARBINARY(MAX)";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARBINARY(" + typeDescriptor.getLength() + ")";
                }
                return "VARBINARY(MAX)";
            case DATE_TYPE:
                return "DATE";
            case TIME_TYPE:
                return typeDescriptor.getTimePrecision() != null ? "TIME(" + typeDescriptor.getTimePrecision() + ")" : "TIME";
            case TIMESTAMP_TYPE:
                if (typeDescriptor.isWithTimezone()) {
                    return typeDescriptor.getTimePrecision() != null ? "DATETIMEOFFSET(" + typeDescriptor.getTimePrecision() + ")" : "DATETIMEOFFSET";
                }
                return typeDescriptor.getTimePrecision() != null ? "DATETIME2(" + typeDescriptor.getTimePrecision() + ")" : "DATETIME2";
            case UUID_TYPE:
                return "UNIQUEIDENTIFIER";
            case XML_TYPE:
                return "XML";
            case GEOMETRY_TYPE:
                return "GEOMETRY";
            case OBJECT_TYPE:
            case JSON_TYPE:
                return "NVARCHAR(MAX)";
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * SQL Server-specific string normalization.
     * Use LTRIM+RTRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(" + quotedCol + ")), " + stringNullReplacement() + ")";
    }

    /**
     * SQL Server-specific integer normalization: CAST to VARCHAR(MAX).
     * Use LTRIM+RTRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(CAST(" + quotedCol + " AS VARCHAR(MAX)))), '')";
    }

    /**
     * SQL Server-specific decimal normalization with configurable decimal places.
     * Use STR with fixed width and decimals, then LTRIM to remove leading spaces.
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
            String roundFunction = rounding ? "ROUND" : "FLOOR";  // SQL Server has no TRUNCATE; use FLOOR instead
            return "COALESCE(LTRIM(RTRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS VARCHAR(MAX)))), '')";
        }


        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(LTRIM(STR(ROUND(" + quotedCol + ", " + precision + "), 38, " + precision + ")), '')";
        } else {
            // Truncate: apply FLOOR then format (SQL Server has no TRUNCATE)
            return "COALESCE(LTRIM(STR(FLOOR(" + quotedCol + " * POWER(10, " + precision + ")) / POWER(10, " + precision + "), 38, " + precision + ")), '')";
        }
    }

    /**
     * SQL Server-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT/REAL is single-precision and may have precision issues.
     * Cast to FLOAT (double precision in SQL Server) first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 6
        int precision = getPrecision("float", 6);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "FLOOR";  // SQL Server has no TRUNCATE; use FLOOR instead
            return "COALESCE(LTRIM(RTRIM(CAST(" + roundFunction + "(CAST(" + quotedCol + " AS FLOAT), 0) AS VARCHAR(MAX)))), '')";
        }


        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(LTRIM(STR(ROUND(CAST(" + quotedCol + " AS FLOAT), " + precision + "), 38, " + precision + ")), '')";
        } else {
            // Truncate: apply FLOOR then format (SQL Server has no TRUNCATE)
            return "COALESCE(LTRIM(STR(FLOOR(CAST(" + quotedCol + " AS FLOAT) * POWER(10, " + precision + ")) / POWER(10, " + precision + "), 38, " + precision + ")), '')";
        }
    }

    /**
     * SQL Server-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(FORMAT(" + quotedCol + ", '" + resolveSqlServerTemporalFormat("date",
                "yyyy-MM-dd", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * SQL Server-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        // FORMAT(time, 'HH:mm:ss') returns NULL on SQL Server (the .NET pattern
        // does not apply to the time type); CONVERT style 114 yields hh:mi:ss.
        // 因此 `normalization.*.time.format` 配置对 SQL Server 不生效：能可靠渲染
        // time 类型的只有 CONVERT 的样式码，.NET 格式串在此类型上会返回 NULL。
        return "COALESCE(CONVERT(varchar(8), " + quotedCol + ", 114), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return "COALESCE(CONVERT(varchar(8), " + quotedCol + ", 114), '')";
    }

    /**
     * SQL Server-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * For DATETIME type (no timezone information):
     * - No timezone conversion needed, just format directly
     *
     * <p>注意：`normalization.*.datetime.timezone` 对 SQL Server 不参与换算——朴素
     * datetime 的解释时区由会话时区决定（与其它方言一致）。此前按“是否配置时区”
     * 切换表达式的写法会让配置与未配置得到两套结果，故移除。
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        // Interpret the naive datetime in the server session zone and convert to
        // the canonical UTC wall clock, matching the mysql-family
        // CONVERT_TZ(session -> UTC) and PostgreSQL AT TIME ZONE conventions.
        // Skipping the conversion desynchronizes SQL Server from every other
        // dialect whose session zone is not UTC.
        String expression = "SWITCHOFFSET(TODATETIMEOFFSET(" + quotedCol
                + ", DATEPART(tz, SYSDATETIMEOFFSET())), '+00:00')";
        return "COALESCE(FORMAT(" + expression + ", '" + resolveSqlServerTemporalFormat("datetime",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * SQL Server-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For DATETIME2/DATETIMEOFFSET type:
     * - Convert to UTC timezone before formatting
     * - This matches MySQL and PostgreSQL behavior
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // datetime/datetime2 map here: SWITCHOFFSET on a NAIVE datetime tags it
        // as UTC without shifting, so interpret it in the server session zone
        // first (same as normalizeDateTime) to reach the canonical UTC wall clock.
        return "COALESCE(FORMAT(SWITCHOFFSET(TODATETIMEOFFSET(" + quotedCol
                + ", DATEPART(tz, SYSDATETIMEOFFSET())), '+00:00'), '"
                + resolveSqlServerTemporalFormat("timestamp",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    /**
     * SQL Server-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        return "COALESCE(FORMAT(SWITCHOFFSET(" + quotedCol + ", '" + resolveSqlServerTimezone("timestamp_with_timezone", "+00:00")
                + "'), '" + resolveSqlServerTemporalFormat("timestamp_with_timezone",
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd") + "'), '')";
    }

    private String resolveSqlServerTimezone(String dataTypeName, String defaultTimezone) {
        String timezone = getTimezone(dataTypeName, defaultTimezone);
        if ("UTC".equalsIgnoreCase(timezone)) {
            return "+00:00";
        }
        return escapeSqlLiteral(timezone);
    }

    private String resolveSqlServerTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("SQL Server", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                sqlServerTemporalTokens(dataTypeName));
    }

    private Set<String> sqlServerTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("yyyy", "yy", "MMMM", "MMM", "MM", "M", "dd", "d");
        Set<String> timeTokens = temporalTokens("HH", "H", "hh", "h", "mm", "m", "ss", "s", "fff", "ff", "f", "tt");
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
     * SQL Server-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(UPPER(CONVERT(NVARCHAR(MAX), " + quotedCol + ", 2)), '')";
    }

    /**
     * SQL Server-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * SQL Server-specific UUID normalization: 统一小写。
     *
     * <p>{@code CAST(uniqueidentifier AS VARCHAR)} 会渲染成大写，而 MySQL/PG/CH 的文本形态是小写，
     * 不统一会让跨源比对把每一行都判为不同（实测 mysql↔sqlserver 全表 c_uuid 误报）。
     * CAST 必须显式给长度：{@code VARCHAR} 默认 30 会截断 36 字符的 UUID。
     */
    @Override
    protected String normalizeUuid(String quotedCol) {
        return "COALESCE(LOWER(LTRIM(RTRIM(CAST(" + quotedCol + " AS VARCHAR(36))))), '')";
    }

    /**
     * SQL Server-specific JSON normalization: 解析后按键排序 + 紧凑序列化。
     *
     * <p>SQL Server 用 NVARCHAR(MAX) 存 JSON 原文，不像 MySQL/PG 的 JSON/JSONB 会在存储时
     * 规范化键序——直接返回原文会把"仅键序不同"判成差异（设计 §2.5 要求键序被抹平，
     * 实测 EQ_015 pk=15 误报）。这里用 OPENJSON 重建：键按名称排序、紧凑无空格，
     * 与 MySQL 侧 {@code REPLACE(REPLACE(CAST(col AS CHAR), ': ', ':'), ', ', ',')} 同形。
     * 仅覆盖扁平对象（数据集的 JSON 列均为对象）；数组等其它形态退化为去空白原文。
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        String canonical = "(SELECT '{' + STRING_AGG('\"' + [key] + '\":'"
                + " + CASE WHEN [type] = 1 THEN '\"' + STRING_ESCAPE([value], 'json') + '\"'"
                + " ELSE [value] END, ',') WITHIN GROUP (ORDER BY [key]) + '}'"
                + " FROM OPENJSON(" + quotedCol + "))";
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' "
                + "WHEN LTRIM(" + quotedCol + ") LIKE '[%' THEN COALESCE(" + canonical + ", '') "
                + "ELSE COALESCE(LTRIM(RTRIM(" + quotedCol + ")), '') END";
    }

    /**
     * SQL Server-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(CAST(" + quotedCol + " AS VARCHAR(MAX)))), '')";
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
            case "money":
            case "smallmoney":
                return DataType.DECIMAL;
            case "float":
                return DataType.DOUBLE;
            case "real":
                return DataType.FLOAT;
            case "varchar":
            case "nvarchar":
                return DataType.VARCHAR;
            case "text":
            case "ntext":
                return DataType.TEXT;
            case "char":
            case "nchar":
                return DataType.CHAR;
            case "date":
                return DataType.DATE;
            case "time":
                return DataType.TIME;
            case "datetime":
            case "datetime2":
            case "smalldatetime":
            case "timestamp":
                return DataType.TIMESTAMP;
            case "datetimeoffset":
                // DATETIMEOFFSET 自带偏移：归一化必须走 TIMESTAMP_WITH_TIMEZONE 分支
                // （SWITCHOFFSET(col,'+00:00') 保持时刻）；按 TIMESTAMP 处理会被当成裸墙钟
                // 再按会话时区解释，整体偏移 8 小时（实测 mysql↔sqlserver 的 c_timestamptz 全表误报）。
                return DataType.TIMESTAMP_WITH_TIMEZONE;
            case "bit":
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "uniqueidentifier":
            case "uuid":
            case "guid":
                return DataType.VARCHAR;
            case "binary":
            case "varbinary":
            case "image":
                return DataType.BLOB;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "NVARCHAR(" + length + ")" : "NVARCHAR(MAX)";
            case CHAR:
                return length > 0 ? "NCHAR(" + length + ")" : "NCHAR(1)";
            case TEXT:
                return "NVARCHAR(MAX)";
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
                return "DECIMAL(18,0)";
            case FLOAT:
                return "REAL";
            case DOUBLE:
                return "FLOAT";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "DATETIME2";
            case BOOLEAN:
                return "BIT";
            case BLOB:
                return "VARBINARY(MAX)";
            case JSON:
                return "NVARCHAR(MAX)";
            default:
                return super.formatDataType(dataType, length, precision, scale);
        }
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null)
            return "NULL";
        return timestamp.toString();
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value;
    }
}
