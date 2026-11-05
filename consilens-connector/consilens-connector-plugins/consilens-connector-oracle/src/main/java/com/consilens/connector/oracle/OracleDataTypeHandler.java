package com.consilens.connector.oracle;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Oracle data type handler.
 */
public class OracleDataTypeHandler extends BaseDataTypeHandler {

    public OracleDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new Oracle data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public OracleDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * 科学计数渲染（与 Java {@code %.6e} 同形）。
     *
     * <p>不能用 {@code TO_CHAR(v,'FM9.999999EEEE')}：Oracle 会先把 BINARY_DOUBLE 隐式转成
     * NUMBER，`1e300` 直接 ORA-01426（numeric overflow）、`1e-300` 退化为 {@code 0.e+00}。
     * 因此与 MySQL 族同法构造：尾数 + 指数，POWER 显式用 BINARY_DOUBLE 求幂，指数先转
     * NUMBER 再 TO_CHAR（否则 {@code TO_CHAR(BINARY_DOUBLE)} 会输出 {@code 3.0E+002} 形式）。
     *
     * <p>已知边界：第 7 位有效数字恰好为 `.5` 的平局值，Oracle 的 ROUND 与 Java 的
     * `%.6e` 可能相差一档（其余取值逐字一致）。
     */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        String e0 = "FLOOR(LOG(10, ABS(" + quotedCol + ")))";
        String mant = "ABS(" + quotedCol + ")/POWER(TO_BINARY_DOUBLE(10), " + e0 + ")";
        String carry = "CASE WHEN ROUND(" + mant + ", 6) >= 10 THEN 1 ELSE 0 END";
        String exp = e0 + " + " + carry;
        String packed = "CASE WHEN " + carry + " = 1 THEN ABS(" + quotedCol
                + ")/POWER(TO_BINARY_DOUBLE(10), " + e0 + " + 1) ELSE " + mant + " END";
        return "CASE WHEN " + quotedCol + " < 0 THEN '-' ELSE '' END || "
                + "TO_CHAR(" + packed + ", 'FM0.000000') || 'e' || "
                + "CASE WHEN (" + exp + ") < 0 THEN '-' ELSE '+' END || "
                + "CASE WHEN ABS(" + exp + ") < 10 THEN '0' || TO_CHAR(CAST(ABS(" + exp + ") AS NUMBER)) "
                + "ELSE TO_CHAR(CAST(ABS(" + exp + ") AS NUMBER)) END";
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
            case "INT2":
            case "SMALLINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).build();
            case "INT4":
            case "INT":
            case "INTEGER":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
            case "NUMBER": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                if (precisionScale[0] != null) {
                    int precision = precisionScale[0];
                    int scale = precisionScale[1] != null ? precisionScale[1] : 0;
                    if (scale == 0) {
                        if (precision <= 4) {
                            return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).build();
                        }
                        if (precision <= 9) {
                            return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
                        }
                        if (precision <= 19) {
                            // NUMBER(19) 是本数据集 bigint/键列的物理形态（见设计 §5.2），
                            // 归一到 64 位整数；真正的 19 位定点列用 NUMBER(20,0) 表达。
                            return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).build();
                        }
                    }
                    return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                            .originType(originType)
                            .numericPrecision(precision)
                            .numericScale(scale)
                            .build();
                }
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            }
            case "BINARY_FLOAT":
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "BINARY_DOUBLE":
            case "DOUBLE PRECISION":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "CHAR":
            case "NCHAR":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType) != null ? extractLength(upperType) : 1).build();
            case "VARCHAR":
            case "VARCHAR2":
            case "NVARCHAR":
            case "NVARCHAR2":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType)).build();
            case "CLOB":
            case "NCLOB":
            case "LONG":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).textType(true).build();
            case "RAW":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).length(extractLength(upperType)).build();
            case "BLOB":
            case "LONG RAW":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BINARY_TYPE).originType(originType).blobType(true).build();
            case "DATE":
                // 默认按 DATE 语义（date-only）渲染，与其它方言的 DATE 列对齐；
                // Oracle DATE 物理上含时分秒，需要保留时可用 normalization.date.comparisonMode=DATETIME 开启。
                return TypeDescriptor.builder(dateIncludesTime()
                        ? com.consilens.common.enums.DataType.TIMESTAMP_TYPE
                        : com.consilens.common.enums.DataType.DATE_TYPE).originType(originType).build();
            case "TIMESTAMP":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).timePrecision(extractLength(upperType)).build();
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMPTZ":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).timePrecision(extractLength(upperType)).withTimezone(true).build();
            case "INTERVAL YEAR TO MONTH":
            case "INTERVAL DAY TO SECOND":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTERVAL_TYPE).originType(originType).build();
            case "ROWID":
            case "UROWID":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).build();
            case "XMLTYPE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.XML_TYPE).originType(originType).build();
            case "SDO_GEOMETRY":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.GEOMETRY_TYPE).originType(originType).build();
            default:
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "VARCHAR2(4000)";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE:
                if (typeDescriptor.getBitWidth() == null || typeDescriptor.getBitWidth() <= 16) return "NUMBER(5,0)";
                if (typeDescriptor.getBitWidth() <= 32) return "NUMBER(10,0)";
                return "NUMBER(19,0)";
            case FLOAT_TYPE:
                return "BINARY_FLOAT";
            case DOUBLE_TYPE:
                return "BINARY_DOUBLE";
            case DECIMAL_TYPE:
                return "NUMBER(" + (typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 38)
                        + "," + (typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0) + ")";
            case STRING_TYPE:
                if (typeDescriptor.isTextType()) {
                    return "CLOB";
                }
                if (typeDescriptor.getLength() != null && typeDescriptor.getLength() > 0) {
                    return "VARCHAR2(" + Math.min(typeDescriptor.getLength(), 4000) + ")";
                }
                return "VARCHAR2(4000)";
            case BINARY_TYPE:
                if (typeDescriptor.isBlobType() || typeDescriptor.getLength() == null || typeDescriptor.getLength() > 2000) {
                    return "BLOB";
                }
                return "RAW(" + typeDescriptor.getLength() + ")";
            case DATE_TYPE:
                return "DATE";
            case TIME_TYPE:
                return "VARCHAR2(32)";
            case TIMESTAMP_TYPE:
                String timestampType = typeDescriptor.isWithTimezone() ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
                return typeDescriptor.getTimePrecision() != null
                        ? timestampType + "(" + typeDescriptor.getTimePrecision() + ")"
                        : timestampType;
            case INTERVAL_TYPE:
                return "INTERVAL DAY TO SECOND";
            case BOOLEAN_TYPE:
                return "NUMBER(1,0)";
            case JSON_TYPE:
            case XML_TYPE:
            case OBJECT_TYPE:
                return "CLOB";
            case UUID_TYPE:
                return "VARCHAR2(36)";
            case GEOMETRY_TYPE:
                return "SDO_GEOMETRY";
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * RAWTOHEX only accepts RAW; on BLOB it raises ORA-00932. BLOB columns are
     * rendered via DBMS_LOB.SUBSTR (first 2000 bytes, enough for the demo data),
     * producing the same upper-case hex as RAWTOHEX on RAW columns.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(RAWTOHEX(DBMS_LOB.SUBSTR(" + quotedCol + ", 2000, 1)), '')";
    }

    /**
     * Oracle-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // TEXT columns are CLOB in Oracle: TRIM/concat on CLOB mixed with CHAR
        // raises ORA-00932, so render through TO_CHAR first.
        return "COALESCE(TRIM(TO_CHAR(" + quotedCol + ")), " + stringNullReplacement() + ")";
    }

    /**
     * Oracle-specific integer normalization: TO_CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(TO_CHAR(" + quotedCol + ")), '')";
    }

    /**
     * Oracle-specific decimal normalization with configurable decimal places.
     * Use TO_CHAR with FM (Fill Mode) to remove leading spaces and ensure specified decimal places.
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
            String roundFunction = rounding ? "ROUND" : "TRUNC";
            return "COALESCE(TRIM(TO_CHAR(" + roundFunction + "(" + quotedCol + ", 0), '"
                    + decimalFormatMask(0).replace(".", "") + "')), '')";
        }

        String formatPattern = decimalFormatMask(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(TO_CHAR(ROUND(" + quotedCol + ", " + precision + "), '" + formatPattern + "'), '')";
        } else {
            // Truncate: apply TRUNC then format
            return "COALESCE(TO_CHAR(TRUNC(" + quotedCol + ", " + precision + "), '" + formatPattern + "'), '')";
        }
    }

    /**
     * Oracle-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to BINARY_DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 6
        int precision = getPrecision("float", 6);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNC";
            return "COALESCE(TRIM(TO_CHAR(" + roundFunction + "(CAST(" + quotedCol
                    + " AS BINARY_DOUBLE), 0), '" + decimalFormatMask(0).replace(".", "") + "')), '')";
        }

        String formatPattern = decimalFormatMask(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(TO_CHAR(ROUND(CAST(" + quotedCol + " AS BINARY_DOUBLE), " + precision + "), '" + formatPattern + "'), '')";
        } else {
            // Truncate: apply TRUNC then format
            return "COALESCE(TO_CHAR(TRUNC(CAST(" + quotedCol + " AS BINARY_DOUBLE), " + precision + "), '" + formatPattern + "'), '')";
        }
    }

    /**
     * Oracle-specific date normalization: YYYY-MM-DD format.
     */
    /**
     * Oracle DATE 物理上含时分秒。默认与其它方言的 DATE 一样只渲染日期；
     * 配置 {@code normalization.date.comparisonMode=DATETIME} 后保留时分秒（并映射为 TIMESTAMP）。
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        if (dateIncludesTime()) {
            return "COALESCE(TO_CHAR(" + quotedCol + ", '" + resolveOracleTemporalFormat("date",
                    "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
        }
        return "COALESCE(TO_CHAR(" + quotedCol + ", '" + resolveOracleTemporalFormat("date",
                "YYYY-MM-DD", "YYYY-MM-DD") + "'), '')";
    }

    /** normalization.date.comparisonMode=DATETIME 时保留 Oracle DATE 的时分秒。 */
    private boolean dateIncludesTime() {
        String mode = getComparisonMode("date");
        return mode != null && "DATETIME".equalsIgnoreCase(mode.trim());
    }

    /**
     * Oracle-specific time normalization: HH24:MI:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", '" + resolveOracleTemporalFormat("time",
                "HH24:MI:SS", "HH24:MI:SS") + "'), '')";
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        String expression = quotedCol;
        String targetTimezone = getTimezone("time_with_timezone", null);
        if (targetTimezone != null && !targetTimezone.isBlank()) {
            expression = quotedCol + " AT TIME ZONE '" + resolveOracleTimezone("time_with_timezone", "UTC") + "'";
        }
        return "COALESCE(TO_CHAR(" + expression + ", '" + resolveOracleTemporalFormat("time_with_timezone",
                "HH24:MI:SS", "HH24:MI:SS") + "'), '')";
    }

    /**
     * Oracle-specific datetime normalization: YYYY-MM-DD HH24:MI:SS format.
     * For DATE type (Oracle DATE contains time):
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        // Always canonicalize to UTC (see normalizeTimestamp note); a
        // config-conditional literal fallback desynchronizes Oracle from the
        // other mysql-family dialects whose session zones differ.
        String expression = "FROM_TZ(CAST(" + quotedCol + " AS TIMESTAMP), SESSIONTIMEZONE) AT TIME ZONE '"
                + resolveOracleTimezone("datetime", "UTC") + "'";
        return "COALESCE(TO_CHAR(" + expression + ", '" + resolveOracleTemporalFormat("datetime",
                "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * Oracle-specific timestamp normalization: YYYY-MM-DD HH24:MI:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMP type:
     * - Convert to UTC timezone before formatting
     * - This matches MySQL and PostgreSQL behavior
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // Interpret the stored wall-clock in the session zone and render UTC,
        // matching the mysql CONVERT_TZ(session -> UTC) convention. TO_CHAR on
        // the raw column keeps the literal (session-zone) wall clock and the
        // local-compare path would leak NLS-formatted strings instead.
        return "COALESCE(TO_CHAR(FROM_TZ(CAST(" + quotedCol + " AS TIMESTAMP), SESSIONTIMEZONE) AT TIME ZONE '"
                + resolveOracleTimezone("timestamp", "UTC") + "', '"
                + resolveOracleTemporalFormat("timestamp",
                "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    /**
     * Oracle-specific timestamp with timezone normalization: YYYY-MM-DD HH24:MI:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE '"
                + resolveOracleTimezone("timestamp_with_timezone", "UTC") + "', '" + resolveOracleTemporalFormat("timestamp_with_timezone",
                "YYYY-MM-DD HH24:MI:SS", "YYYY-MM-DD") + "'), '')";
    }

    private String resolveOracleTimezone(String dataTypeName, String defaultTimezone) {
        return escapeSqlLiteral(getTimezone(dataTypeName, defaultTimezone));
    }

    private String resolveOracleTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("Oracle", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                oracleTemporalTokens(dataTypeName));
    }

    private Set<String> oracleTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("YYYY", "YY", "MONTH", "MON", "MM", "DD");
        Set<String> timeTokens = temporalTokens("HH24", "HH12", "HH", "MI", "SS", "FF", "FF1", "FF2", "FF3",
                "FF4", "FF5", "FF6", "FF7", "FF8", "FF9", "AM", "PM");
        if ("date".equals(dataTypeName)) {
            Set<String> tokens = new HashSet<>(dateTokens);
            if (dateIncludesTime()) {
                tokens.addAll(timeTokens);
            }
            return tokens;
        }
        if ("time".equals(dataTypeName) || "time_with_timezone".equals(dataTypeName)) {
            return timeTokens;
        }
        dateTokens.addAll(timeTokens);
        return dateTokens;
    }

    /**
     * Oracle-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * Oracle-specific JSON normalization: convert to CLOB then to VARCHAR2.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + "), '')";
    }

    /**
     * Oracle-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(TO_CHAR(" + quotedCol + ")), '')";
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null) {
            return DataType.UNKNOWN;
        }
        String type = sourceType.toLowerCase();

        // Handle types with precision/scale (e.g., NUMBER(10,2))
        if (type.startsWith("number")) {
            return convertNumberType(sourceType);
        }

        switch (type) {
            case "varchar2":
            case "nvarchar2":
            case "varchar":
                return DataType.VARCHAR;
            case "char":
            case "nchar":
                return DataType.CHAR;
            case "clob":
            case "nclob":
            case "long":
                return DataType.TEXT;
            case "date":
                // 默认 Map to DATE（date-only），与 mysql DATE 对齐；
                // comparisonMode=DATETIME 时映射为 TIMESTAMP，保留 Oracle DATE 的时分秒。
                return dateIncludesTime() ? DataType.TIMESTAMP : DataType.DATE;
            case "timestamp":
            case "timestamp with time zone":
            case "timestamp with local time zone":
                return DataType.TIMESTAMP;
            case "float":
            case "binary_float":
                return DataType.FLOAT;
            case "double precision":
            case "binary_double":
                return DataType.DOUBLE;
            case "raw":
            case "long raw":
                return DataType.VARBINARY;
            case "blob":
            case "bfile":
                return DataType.BLOB;
            case "rowid":
            case "urowid":
                return DataType.VARCHAR;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    private DataType convertNumberType(String sourceType) {
        // Parse precision and scale from "NUMBER(p,s)" or "NUMBER(p)"
        // Simple parsing logic
        if (sourceType.equalsIgnoreCase("number")) {
            return DataType.DECIMAL;
        }

        try {
            String params = sourceType.substring(sourceType.indexOf('(') + 1, sourceType.indexOf(')'));
            String[] parts = params.split(",");
            int precision = Integer.parseInt(parts[0].trim());
            int scale = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;

            if (scale == 0) {
                if (precision == 1) {
                    return DataType.BOOLEAN;
                } else if (precision <= 3) {
                    return DataType.TINYINT;
                } else if (precision <= 5) {
                    return DataType.SMALLINT;
                } else if (precision <= 10) {
                    return DataType.INTEGER;
                } else if (precision <= 19) {
                    return DataType.BIGINT;
                } else {
                    return DataType.DECIMAL; // Fits in BigDecimal
                }
            } else {
                return DataType.DECIMAL;
            }
        } catch (Exception e) {
            // Fallback if parsing fails
            return DataType.DECIMAL;
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR2(" + length + ")" : "VARCHAR2(4000)";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "CLOB";
            case INTEGER:
                return "NUMBER(38)"; // Oracle uses NUMBER(38) for safe integer storage
            case BIGINT:
                return "NUMBER(19)";
            case SMALLINT:
                return "NUMBER(5)";
            case TINYINT:
                return "NUMBER(3)";
            case BOOLEAN:
                return "NUMBER(1)";
            case DECIMAL:
                if (precision > 0) {
                    return "NUMBER(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "NUMBER";
            case FLOAT:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case DATE:
                return "DATE";
            case TIMESTAMP:
                return "TIMESTAMP";
            case TIMESTAMP_WITH_TIMEZONE:
                return "TIMESTAMP WITH TIME ZONE";
            case BLOB:
                return "BLOB";
            case JSON:
                return "CLOB"; // Oracle 12c+ has JSON, but CLOB is safer for compatibility
            default:
                return super.formatDataType(dataType, length, precision, scale);
        }
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null)
            return "NULL";
        return "TO_TIMESTAMP('" + timestamp + "', 'YYYY-MM-DD HH24:MI:SS')";
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value;
    }
}
