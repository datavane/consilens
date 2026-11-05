package com.consilens.conncetor.base;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.LegacyTypeMapper;
import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class BaseDataTypeHandler implements DataTypeHandler {

    private static final Pattern TYPE_PARAMS_PATTERN = Pattern.compile("^\\s*([^()]+?)(?:\\(([^)]*)\\))?\\s*$");
    private static final Pattern LENGTH_PATTERN = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern PRECISION_SCALE_PATTERN = Pattern.compile("\\((\\d+)\\s*(?:,\\s*(\\d+))?\\)");

    private static final Map<String, DataType> DATA_TYPE_ALIASES = Map.ofEntries(
            Map.entry("BOOL", DataType.BOOLEAN),
            Map.entry("BIT", DataType.BIT),
            Map.entry("BPCHAR", DataType.CHAR),
            Map.entry("INT", DataType.INTEGER),
            Map.entry("INT2", DataType.SMALLINT),
            Map.entry("INT4", DataType.INTEGER),
            Map.entry("INT8", DataType.BIGINT),
            Map.entry("DOUBLE_PRECISION", DataType.DOUBLE),
            Map.entry("CHARACTER_VARYING", DataType.VARCHAR),
            Map.entry("CHARACTER", DataType.CHAR),
            Map.entry("ENUM", DataType.VARCHAR),
            Map.entry("SET", DataType.VARCHAR),
            Map.entry("TIMESTAMP_WITH_TIME_ZONE", DataType.TIMESTAMP_WITH_TIMEZONE),
            Map.entry("TIMESTAMP_WITHOUT_TIME_ZONE", DataType.TIMESTAMP),
            Map.entry("TIME_WITH_TIME_ZONE", DataType.TIME_WITH_TIME_ZONE),
            Map.entry("TIME_WITHOUT_TIME_ZONE", DataType.TIME),
            Map.entry("DATETIME2", DataType.DATETIME)
    );

    private final CapabilityProvider capabilityProvider;
    protected final Map<String, ?> normalizationConfig;

    public BaseDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    public BaseDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        this.capabilityProvider = capabilityProvider;
        this.normalizationConfig = normalizationConfig;
    }
    
    /**
     * Returns the configured precision for the given data type.
     *
     * @param dataTypeName data type name in lowercase (e.g. "decimal", "float")
     * @param defaultPrecision fallback precision when no config is present
     * @return configured precision, or defaultPrecision if not set
     */
    /**
     * Oracle / PostgreSQL {@code TO_CHAR} 的数值格式掩码：{@code FM999…990.0000}。
     *
     * <p>整数位必须覆盖到 38 位（Oracle {@code NUMBER} 的最大精度，也是 PostgreSQL
     * {@code numeric} 的常见上限）。原来只给 15 位，超过 10^15 的值会被两个库都渲染成一串
     * {@code #}——**不同的值渲染成同一个字符串**，差异因此被漏掉（比误报严重）。
     */
    protected static String decimalFormatMask(int precision) {
        return "FM" + "9".repeat(DECIMAL_INTEGER_DIGITS - 1) + "0." + "0".repeat(Math.max(0, precision));
    }

    /** 整数位（含强制显示的 0）。 */
    protected static final int DECIMAL_INTEGER_DIGITS = 38;

    protected int getPrecision(String dataTypeName, int defaultPrecision) {
        Integer precision = getRuleValue(dataTypeName, "getPrecision", Integer.class);
        return precision != null ? precision : defaultPrecision;
    }

    /**
     * Returns the configured rounding flag for the given data type.
     *
     * @param dataTypeName data type name in lowercase (e.g. "decimal", "float")
     * @param defaultRounding fallback rounding flag when no config is present
     * @return configured rounding flag, or defaultRounding if not set
     */
    protected boolean getRounding(String dataTypeName, boolean defaultRounding) {
        Boolean rounding = getRuleValue(dataTypeName, "getRounding", Boolean.class);
        return rounding != null ? rounding : defaultRounding;
    }

    /**
     * 归一化规则里的比较模式（如 DATE_ONLY / TRUNCATE_TO_DAY / DATETIME）。
     * 供方言 handler 决定同一物理类型在比较时按哪种形态渲染。
     */
    protected String getComparisonMode(String dataTypeName) {
        return getRuleValue(dataTypeName, "getComparisonMode", String.class);
    }

    protected String getTimezone(String dataTypeName, String defaultTimezone) {
        String timezone = getRuleValue(dataTypeName, "getTimezone", String.class);
        return timezone != null ? timezone : defaultTimezone;
    }

    protected String getNativeTemporalFormat(String connectorName,
                                             String dataTypeName,
                                             String defaultFormat,
                                             String dateOnlyDefaultFormat,
                                             Set<String> allowedTokens) {
        String configuredFormat = getRuleValue(dataTypeName, "getFormat", String.class);
        String comparisonMode = getComparisonMode(dataTypeName);
        boolean dateOnlyComparison = comparisonMode != null
                && ("DATE_ONLY".equalsIgnoreCase(comparisonMode.trim())
                        || "TRUNCATE_TO_DAY".equalsIgnoreCase(comparisonMode.trim()));
        String effectiveDefault = dateOnlyComparison ? dateOnlyDefaultFormat : defaultFormat;
        String format = configuredFormat == null || configuredFormat.isBlank()
                ? effectiveDefault
                : configuredFormat.trim();
        validateNativeTemporalFormat(connectorName, dataTypeName, format, allowedTokens);
        return escapeSqlLiteral(format);
    }

    protected Set<String> temporalTokens(String... tokens) {
        Set<String> result = new HashSet<>();
        for (String token : tokens) {
            result.add(token);
        }
        return result;
    }

    protected void validateNativeTemporalFormat(String connectorName,
                                                String dataTypeName,
                                                String format,
                                                Set<String> allowedTokens) {
        if (format == null || format.isBlank()) {
            throw new ConnectorException("Temporal format for " + connectorName + " " + dataTypeName
                    + " must not be empty");
        }
        if (allowedTokens == null || allowedTokens.isEmpty()) {
            throw new ConnectorException("Temporal format validation tokens for " + connectorName + " "
                    + dataTypeName + " must not be empty");
        }

        List<String> tokens = new ArrayList<>(allowedTokens);
        tokens.sort(Comparator.comparingInt(String::length).reversed());

        boolean matchedToken = false;
        int index = 0;
        while (index < format.length()) {
            char ch = format.charAt(index);
            String matched = matchToken(format, index, tokens);
            if (matched != null) {
                matchedToken = true;
                index += matched.length();
                continue;
            }
            if (ch == '%') {
                throw new ConnectorException("Unsupported temporal format '" + format + "' for " + connectorName
                        + " " + dataTypeName + " at token starting '" + format.substring(index) + "'");
            }
            if (!isTemporalFormatLiteral(ch)) {
                throw new ConnectorException("Unsupported temporal format literal '" + ch + "' in '" + format
                        + "' for " + connectorName + " " + dataTypeName);
            }
            index++;
        }

        if (!matchedToken) {
            throw new ConnectorException("Temporal format '" + format + "' for " + connectorName + " "
                    + dataTypeName + " must contain at least one supported token");
        }
    }

    private String matchToken(String format, int index, List<String> tokens) {
        for (String token : tokens) {
            if (token.startsWith("%")) {
                if (format.startsWith(token, index)) {
                    return token;
                }
            } else if (format.startsWith(token, index)) {
                return token;
            }
        }
        return null;
    }

    private boolean isTemporalFormatLiteral(char ch) {
        return Character.isDigit(ch) || ch == '-' || ch == '/' || ch == ':' || ch == ' '
                || ch == 'T' || ch == '.' || ch == '_' || ch == ',' || ch == '\'' || ch == '"';
    }

    protected String escapeSqlLiteral(String value) {
        return value == null ? null : value.replace("'", "''");
    }

    private <T> T getRuleValue(String dataTypeName, String getterName, Class<T> valueType) {
        if (normalizationConfig == null) {
            return null;
        }

        try {
            Object rule = findRule(dataTypeName);
            if (rule == null) {
                return null;
            }
            Method getter = rule.getClass().getMethod(getterName);
            Object value = getter.invoke(rule);
            return valueType.isInstance(value) ? valueType.cast(value) : null;
        } catch (Exception e) {
            log.debug("Failed to get normalization config '{}' for type '{}': {}", getterName, dataTypeName, e.getMessage());
            return null;
        }
    }

    private Object findRule(String dataTypeName) {
        Object rule = normalizationConfig.get(dataTypeName);
        if (rule != null) {
            return rule;
        }
        String fallback = fallbackRuleKey(dataTypeName);
        return fallback != null ? normalizationConfig.get(fallback) : null;
    }

    private String fallbackRuleKey(String dataTypeName) {
        if ("timestamp_with_timezone".equals(dataTypeName)) {
            return "timestamp";
        }
        if ("time_with_timezone".equals(dataTypeName)) {
            return "time";
        }
        return null;
    }

    @Override
    public String normalizeColumn(String columnName, DataType dataType) {
        String quotedCol = capabilityProvider.quote(columnName);

        // Log normalization at DEBUG level
        log.debug("BaseDataTypeHandler.normalizeColumn: column='{}', dataType={}", columnName, dataType);

        return normalizeExpression(quotedCol, dataType);
    }

    /**
     * 归一化分派（入参必须是已可用的列表达式，不再做引用包装）。
     */
    @Override
    public String normalizeExpression(String quotedCol, DataType dataType) {

        if (dataType == null || dataType == DataType.UNKNOWN) {
            log.warn("Unsupported data type normalization for column '{}', falling back to default normalization", quotedCol);
            return normalizeDefault(quotedCol);
        }
        
        switch (dataType) {
            case VARCHAR:
            case CHAR:
            case TEXT:
            case CLOB:
            case LONGVARCHAR:
                log.debug("  -> Using normalizeString");
                return normalizeString(quotedCol);

            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                log.debug("  -> Using normalizeInteger");
                return normalizeInteger(quotedCol);

            case FLOAT:
            case REAL:
            case DOUBLE:
                // DOUBLE 与 FLOAT 同属浮点族，且必须与本地路径 ValueNormalizer 同口径：
                // 常规区间定点 6 位小数，超出 %e 区间（|v| > 1e10 或 0 < |v| < 1e-10）用科学计数。
                log.debug("  -> Using normalizeFloatValue");
                return normalizeFloatValue(quotedCol);

            case DECIMAL:
                log.debug("  -> Using normalizeDecimal");
                return normalizeDecimal(quotedCol);

            case BOOLEAN:
            case BIT:
                log.debug("  -> Using normalizeBoolean");
                return normalizeBoolean(quotedCol);

            case DATE:
                log.debug("  -> Using normalizeDate");
                return normalizeDate(quotedCol);

            case TIME:
                log.debug("  -> Using normalizeTime");
                return normalizeTime(quotedCol);

            case TIME_WITH_TIME_ZONE:
                log.debug("  -> Using normalizeTimeWithTimezone");
                return normalizeTimeWithTimezone(quotedCol);

            case DATETIME:
                log.debug("  -> Using normalizeDateTime");
                return normalizeDateTime(quotedCol);

            case TIMESTAMP:
                log.debug("  -> Using normalizeTimestamp");
                return normalizeTimestamp(quotedCol);

            case TIMESTAMP_WITH_TIMEZONE:
                log.debug("  -> Using normalizeTimestampWithTimezone");
                return normalizeTimestampWithTimezone(quotedCol);

            case BINARY:
            case VARBINARY:
            case BLOB:
            case LONGBLOB:
                log.debug("  -> Using normalizeBlob");
                return normalizeBlob(quotedCol);

            case JSON:
            case JSONB:
                log.debug("  -> Using normalizeJson");
                return normalizeJson(quotedCol);

            case UUID:
                log.debug("  -> Using normalizeUuid");
                return normalizeUuid(quotedCol);

            default:
                log.warn("Unsupported data type normalization for column '{}': {}, falling back to default normalization",
                        quotedCol, dataType);
                return normalizeDefault(quotedCol);
        }
    }

    /**
     * UUID 归一化：统一小写（RFC 4122 的规范渲染）。
     *
     * <p>各方言对同一 UUID 值的渲染大小写不同（SQL Server 的 {@code UNIQUEIDENTIFIER} →
     * 大写，MySQL/PG/CH 的文本形态 → 小写），不做统一会在跨源比对时把每一行都判为不同。
     */
    protected String normalizeUuid(String quotedCol) {
        return "COALESCE(LOWER(TRIM(CAST(" + quotedCol + " AS VARCHAR))), '')";
    }

    /**
     * Normalize string types (VARCHAR, CHAR, TEXT, CLOB, LONGVARCHAR).
     * Default: TRIM and convert NULL to the configured null sentinel.
     *
     * <p>When {@code normalization.<side>.string.nullValue} is configured, NULL is
     * rendered with that sentinel instead of {@code ''}, which makes NULL
     * distinguishable from an empty string. Callers that want NULL and {@code ''}
     * to stay indistinguishable (the historical behaviour) simply omit the
     * {@code nullValue} parameter.
     */
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), " + stringNullReplacement() + ")";
    }

    /**
     * SQL literal used as the NULL replacement for string types.
     *
     * <p>Returns {@code ''} unless {@code normalization.<side>.string.nullValue} is
     * configured, in which case it returns that literal (single quotes escaped).
     * Kept in a shared helper so every dialect override renders the sentinel
     * identically.
     */
    protected String stringNullReplacement() {
        String nullValue = getNullValue("string");
        return nullValue != null ? "'" + escapeSqlLiteral(nullValue) + "'" : "''";
    }

    /**
     * Reads the configured {@code nullValue} for the given normalized type name
     * (e.g. {@code "string"}, {@code "boolean"}).
     *
     * @return the configured sentinel, or {@code null} when unset
     */
    protected String getNullValue(String dataTypeName) {
        return getRuleValue(dataTypeName, "getNullValue", String.class);
    }

    /**
     * Folds negative zero into positive zero at the SQL level.
     *
     * <p>Renderings differ across engines: some discard the sign when a value
     * rounds to zero ({@code -0.0} → {@code '0.0000'}), others keep it
     * ({@code '-0.0000'}). Wrapping the numeric expression in
     * {@code CASE WHEN x = 0 THEN 0 ELSE x END} makes {@code -0.0} and {@code 0.0}
     * normalize to the same string on every dialect, while leaving NULL
     * untouched (the comparison is unknown for NULL, so the ELSE branch is
     * taken and the surrounding COALESCE still applies).
     */
    protected String foldNegativeZero(String quotedCol) {
        return "(CASE WHEN " + quotedCol + " = 0 THEN 0 ELSE " + quotedCol + " END)";
    }

    /**
     * Normalize integer types (TINYINT, SMALLINT, INTEGER, BIGINT).
     * Default: Convert to string, NULL becomes '0'.
     */
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize decimal types (DECIMAL, FLOAT, DOUBLE, REAL).
     * Default: Format with 4 decimal places, NULL becomes '0.0000'.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeDecimal(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + foldNegativeZero(quotedCol) + " AS VARCHAR), '')";
    }

    /**
     * Normalize float type (single-precision floating point).
     * Default: Cast to DOUBLE first to avoid precision loss, then format.
     * Subclasses should override with database-specific formatting.
     * 
     * Note: FLOAT is a single-precision type that may have precision issues.
     * Converting to DOUBLE first helps maintain consistency.
     */
    /**
     * 浮点归一化入口：按设计 §2.5 的窗口选择定点/科学计数两种渲染。
     *
     * <p>本地路径 {@link ValueNormalizer#normalizeFloatingPoint} 对 {@code |v| > 1e10} 或
     * {@code 0 < |v| < 1e-10} 使用 {@code String.format("%.6e", v)}；SQL 侧必须给出**同形**
     * 字符串，否则同一份数据在 checksum 段校验与本地逐行比较之间会出现口径差异
     * （实测：1e-300 与 2e-300 在定点 6 位下都被渲染成 {@code 0.000000}，差异被抹平）。
     *
     * @param quotedCol 已可用的列表达式（可能带表别名）
     */
    protected String normalizeFloatValue(String quotedCol) {
        String fixed = normalizeFloat(quotedCol);
        String scientific = scientificFloatExpression(quotedCol);
        if (scientific == null) {
            return fixed;
        }
        String extreme = "(" + quotedCol + " IS NOT NULL AND (ABS(" + quotedCol + ") > 1e10"
                + " OR (ABS(" + quotedCol + ") < 1e-10 AND " + quotedCol + " <> 0)))";
        return "CASE WHEN " + extreme + " THEN " + scientific + " ELSE " + fixed + " END";
    }

    /**
     * 科学计数渲染表达式，必须与 Java {@code String.format("%.6e", v)} 完全同形
     * （如 {@code 1.000000e+300}、{@code 2.500000e-300}，指数至少 2 位、带符号、小写 e）。
     *
     * <p>返回 {@code null} 表示该方言暂未提供等价渲染，此时退化为定点口径（保持既有行为）。
     */
    protected String scientificFloatExpression(String quotedCol) {
        return null;
    }

    /**
     * MySQL 族（mysql/doris/starrocks/tidb/oceanbase）共用的科学计数渲染构造：尾数 + 指数。
     *
     * <p>与 Java {@code String.format("%.6e", v)} 同形：尾数用 {@code v / 10^floor(log10|v|)}，
     * 指数为正时用除法、为负时改用 {@code v * 10^|exp|} 乘法（部分方言 {@code POW(10,-300)}
     * 会下溢为 0）。尾数经 {@code DECIMAL(20,6)} 四舍五入，进位（≥10）时指数进一。
     *
     * <p>已验证（mysql/starrocks 实测逐字一致）：{@code 1e300→1.000000e+300}、
     * {@code 1e-300→1.000000e-300}、{@code 9.9999995e10→1.000000e+11}（进位）、
     * {@code 1.0000005e-11→1.000001e-11}（第 7 位四舍五入）。本方法是该族方言
     * {@link #scientificFloatExpression(String)} 的唯一实现，五个方言 handler 仅做转发。
     */
    protected static String mysqlFamilyScientificFloatExpression(String quotedCol) {
        String e0 = "FLOOR(LOG10(ABS(" + quotedCol + ")))";
        String mant = scaleByPower("ABS(" + quotedCol + ")", e0, "POW");
        String carry = "CASE WHEN ROUND(" + mant + ", 6) >= 10 THEN 1 ELSE 0 END";
        String exp = e0 + " + " + carry;
        String packed = "CASE WHEN " + carry + " = 1 THEN "
                + scaleByPower("ABS(" + quotedCol + ")", e0 + " + 1", "POW") + " ELSE " + mant + " END";
        return "CONCAT("
                + "IF(" + quotedCol + " < 0, '-', ''), "
                + "CAST(" + packed + " AS DECIMAL(20,6)), 'e', "
                + "IF((" + exp + ") < 0, '-', '+'), "
                + "IF(ABS(" + exp + ") < 10, CONCAT('0', ABS(" + exp + ")), "
                + "CAST(ABS(" + exp + ") AS CHAR)))";
    }

    /**
     * {@code value / 10^exponent}；指数为负时改为乘法，规避极小幂下溢。
     *
     * @param powerFunction 方言的幂函数名：MySQL 族为 {@code POW}，Presto/Trino 用 {@code POWER}
     */
    protected static String scaleByPower(String value, String exponent, String powerFunction) {
        return "CASE WHEN (" + exponent + ") >= 0 THEN " + value + "/" + powerFunction + "(10, " + exponent + ") "
                + "ELSE " + value + "*" + powerFunction + "(10, -(" + exponent + ")) END";
    }

    protected String normalizeFloat(String quotedCol) {
        // Generic fallback - cast to DOUBLE then format
        // Subclasses should override this with database-specific logic
        return normalizeDecimal(quotedCol);
    }

    /**
     * Normalize boolean type.
     * Default: Convert to '0' or '1'.
     */
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = TRUE THEN '1' ELSE '0' END";
    }

    /**
     * Normalize date type.
     * Default: Format as 'YYYY-MM-DD'.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeDate(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize time type.
     * Default: Format as 'HH24:MI:SS'.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeTime(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    protected String normalizeTimeWithTimezone(String quotedCol) {
        return normalizeTime(quotedCol);
    }

    /**
     * Normalize datetime/timestamp type.
     * Default: Format as 'YYYY-MM-DD HH24:MI:SS'.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeDateTime(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize timestamp type.
     * Default: Format as 'YYYY-MM-DD HH24:MI:SS'.
     * Subclasses should override with database-specific formatting.
     * 
     * Note: This is separate from normalizeDateTime() to allow different
     * timezone handling for DATETIME vs TIMESTAMP types.
     */
    protected String normalizeTimestamp(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize timestamp with timezone type.
     * Default: Convert to UTC and format as 'YYYY-MM-DD HH24:MI:SS'.
     * Subclasses should override with database-specific formatting.
     * 
     * Note: This handles timezone-aware timestamps (PostgreSQL TIMESTAMPTZ, MySQL TIMESTAMP).
     */
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize binary/blob type.
     * Default: Convert to hexadecimal string.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeBlob(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Normalize JSON/JSONB type.
     * Default: Convert to string.
     */
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '')";
    }

    /**
     * Default normalization for unknown types.
     */
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return DataType.UNKNOWN;
        }

        String canonicalType = canonicalizeTypeName(sourceType);
        DataType aliasType = DATA_TYPE_ALIASES.get(canonicalType);
        if (aliasType != null) {
            return aliasType;
        }

        try {
            return DataType.valueOf(canonicalType);
        } catch (IllegalArgumentException e) {
            for (DataType dt : DataType.values()) {
                if (dt.getDisplayName().equalsIgnoreCase(canonicalType)
                        || dt.getDisplayName().replace(' ', '_').equalsIgnoreCase(canonicalType)) {
                    return dt;
                }
            }
            return DataType.UNKNOWN;
        }
    }

    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        TypeDescriptor baseDescriptor = LegacyTypeMapper.toTypeDescriptor(convertToDataType(originType), originType);
        TypeDescriptor.Builder builder = baseDescriptor.toBuilder();
        ParsedType parsedType = parseType(originType);
        String canonicalType = parsedType.canonicalType;

        if (canonicalType.endsWith("_UNSIGNED")) {
            builder.unsigned(true);
        }

        switch (convertToDataType(originType)) {
            case TINYINT:
                builder.bitWidth(8);
                break;
            case SMALLINT:
                builder.bitWidth(16);
                break;
            case INTEGER:
                builder.bitWidth(32);
                break;
            case BIGINT:
                builder.bitWidth(64);
                break;
            case DECIMAL:
            case NUMERIC:
                builder.numericPrecision(parsedType.integerAt(0, 38));
                builder.numericScale(parsedType.integerAt(1, 0));
                break;
            case CHAR:
            case VARCHAR:
            case BINARY:
            case VARBINARY:
                Integer length = parsedType.integerAt(0, null);
                if (length != null) {
                    builder.length(length);
                }
                break;
            case TEXT:
            case CLOB:
            case LONGVARCHAR:
                builder.textType(true);
                break;
            case BLOB:
            case LONGBLOB:
                builder.blobType(true);
                break;
            case TIME:
            case TIME_WITH_TIME_ZONE:
            case TIMESTAMP:
            case DATETIME:
            case TIMESTAMP_WITH_TIMEZONE:
                Integer precision = parsedType.integerAt(0, null);
                if (precision != null) {
                    builder.timePrecision(precision);
                }
                break;
            default:
                break;
        }

        return builder.build();
    }

    private String canonicalizeTypeName(String sourceType) {
        String upperType = sourceType.trim().toUpperCase(Locale.ROOT);
        upperType = upperType.replaceAll("\\s*\\([^)]*\\)", "");
        upperType = upperType.replaceAll("\\s+", " ").trim();
        return upperType.replace(' ', '_');
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        DataType legacyType = LegacyTypeMapper.toLegacyDataType(typeDescriptor);
        switch (legacyType) {
            case VARCHAR:
            case CHAR:
            case BINARY:
            case VARBINARY:
                return formatDataType(legacyType, safeValue(typeDescriptor.getLength()), 0, 0);
            case DECIMAL:
            case NUMERIC:
                return formatDataType(legacyType, 0, safeValue(typeDescriptor.getNumericPrecision()),
                        safeValue(typeDescriptor.getNumericScale()));
            case TIME:
            case TIME_WITH_TIME_ZONE:
            case TIMESTAMP:
            case TIMESTAMP_WITH_TIMEZONE:
                if (typeDescriptor.getTimePrecision() != null) {
                    return legacyType.getDisplayName() + "(" + typeDescriptor.getTimePrecision() + ")";
                }
                return legacyType.getDisplayName();
            default:
                return formatDataType(legacyType, safeValue(typeDescriptor.getLength()),
                        safeValue(typeDescriptor.getNumericPrecision()), safeValue(typeDescriptor.getNumericScale()));
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision,
            int scale) {
        if (dataType == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(dataType.getDisplayName());
        if (length > 0 && dataType.isString()) {
            sb.append("(").append(length).append(")");
        } else if (precision > 0 && dataType.isNumeric()) {
            sb.append("(").append(precision);
            if (scale > 0) {
                sb.append(",").append(scale);
            }
            sb.append(")");
        }
        return sb.toString();
    }

    @Override
    public Object convertValue(Object value, String targetType) {
        // Default implementation - just return the value
        return value;
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null) {
            return "NULL";
        }
        return "'" + timestamp.toString() + "'";
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value; // Default implementation
    }

    /**
     * Helper method to check if a data type is numeric.
     */
    protected boolean isNumericType(String dataType) {
        return dataType.startsWith("INT") ||
                dataType.equals("BIGINT") ||
                dataType.equals("SMALLINT") ||
                dataType.equals("TINYINT") ||
                dataType.equals("DECIMAL") ||
                dataType.equals("NUMERIC") ||
                dataType.equals("FLOAT") ||
                dataType.equals("DOUBLE") ||
                dataType.equals("REAL") ||
                dataType.equals("DOUBLE PRECISION");
    }

    /**
     * Helper method to check if a data type is character-based.
     */
    protected boolean isCharacterType(String dataType) {
        return dataType.startsWith("VARCHAR") ||
                dataType.startsWith("CHAR") ||
                dataType.equals("TEXT") ||
                dataType.startsWith("NVARCHAR") ||
                dataType.startsWith("NCHAR") ||
                dataType.equals("CLOB") ||
                dataType.equals("NCLOB");
    }

    /**
     * Helper method to check if a data type is date/time.
     */
    protected boolean isDateTimeType(String dataType) {
        return dataType.equals("DATE") ||
                dataType.equals("DATETIME") ||
                dataType.equals("TIMESTAMP") ||
                dataType.equals("TIME") ||
                dataType.equals("YEAR") ||
                dataType.equals("TIMESTAMPTZ");
    }

    /**
     * Helper method to check if a data type is boolean.
     */
    protected boolean isBooleanType(String dataType) {
        return dataType.equals("BOOLEAN") ||
                dataType.equals("BOOL") ||
                dataType.equals("BIT") ||
                dataType.equals("TINYINT(1)");
    }

    protected String normalizeTypeExpression(String originType) {
        return originType == null ? "" : originType.trim().toUpperCase(Locale.ROOT);
    }

    protected String extractBaseType(String typeExpression) {
        if (typeExpression == null || typeExpression.isBlank()) {
            return "";
        }
        String trimmed = typeExpression.trim();
        int idx = trimmed.indexOf('(');
        if (idx > 0) {
            trimmed = trimmed.substring(0, idx).trim();
        }
        if (trimmed.toUpperCase(Locale.ROOT).endsWith(" UNSIGNED")) {
            trimmed = trimmed.substring(0, trimmed.length() - " UNSIGNED".length()).trim();
        }
        return trimmed;
    }

    protected Integer extractLength(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        Matcher matcher = LENGTH_PATTERN.matcher(typeExpression);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    protected Integer[] extractPrecisionScale(String typeExpression) {
        if (typeExpression == null) {
            return new Integer[]{null, null};
        }
        Matcher matcher = PRECISION_SCALE_PATTERN.matcher(typeExpression);
        if (!matcher.find()) {
            return new Integer[]{null, null};
        }
        Integer precision = Integer.parseInt(matcher.group(1));
        Integer scale = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : null;
        return new Integer[]{precision, scale};
    }

    protected boolean containsMaxLength(String typeExpression) {
        return typeExpression != null && typeExpression.toUpperCase(Locale.ROOT).contains("(MAX)");
    }

    protected List<String> splitTopLevel(String input) {
        List<String> parts = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        int parenthesesDepth = 0;
        int angleDepth = 0;
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (!inSingleQuotes && !inDoubleQuotes) {
                if (ch == '(') {
                    parenthesesDepth++;
                } else if (ch == ')') {
                    parenthesesDepth--;
                } else if (ch == '<') {
                    angleDepth++;
                } else if (ch == '>') {
                    angleDepth--;
                } else if (ch == ',' && parenthesesDepth == 0 && angleDepth == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    protected int findTopLevelCharacter(String input, char target) {
        if (input == null) {
            return -1;
        }
        int parenthesesDepth = 0;
        int angleDepth = 0;
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (!inSingleQuotes && !inDoubleQuotes) {
                if (ch == '(') {
                    parenthesesDepth++;
                } else if (ch == ')') {
                    parenthesesDepth--;
                } else if (ch == '<') {
                    angleDepth++;
                } else if (ch == '>') {
                    angleDepth--;
                } else if (ch == target && parenthesesDepth == 0 && angleDepth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private ParsedType parseType(String sourceType) {
        if (sourceType == null) {
            return new ParsedType(DataType.UNKNOWN.name(), null);
        }
        Matcher matcher = TYPE_PARAMS_PATTERN.matcher(sourceType.trim());
        if (!matcher.matches()) {
            return new ParsedType(canonicalizeTypeName(sourceType), null);
        }
        return new ParsedType(canonicalizeTypeName(matcher.group(1)), matcher.group(2));
    }

    private int safeValue(Integer value) {
        return value != null ? value : 0;
    }

    private static final class ParsedType {
        private final String canonicalType;
        private final String[] parts;

        private ParsedType(String canonicalType, String rawParameters) {
            this.canonicalType = canonicalType;
            this.parts = rawParameters == null || rawParameters.isBlank()
                    ? new String[0]
                    : rawParameters.split("\\s*,\\s*");
        }

        private Integer integerAt(int index, Integer fallback) {
            if (index >= parts.length) {
                return fallback;
            }
            try {
                return Integer.parseInt(parts[index].trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * 会话时区到目标时区的平移表达式（MySQL 兼容方言：MySQL/TiDB/Doris/StarRocks/OceanBase）。
     *
     * CONVERT_TZ 内部按 32 位 TIMESTAMP 求值：UTC 落点早于 1970-01-01 00:00:01 或晚于
     * 2038-01-19 03:14:07 时会静默返回原值（不报错、不转换），导致 pre-1970 / post-2038
     * 的 DATETIME 与其他方言（如 PostgreSQL 的 AT TIME ZONE 链）渲染不一致。这里改用
     * 范围内锚点先求出会话时区相对目标时区的固定偏移秒数，再以 INTERVAL 算术平移任意
     * DATETIME 值（64 位安全，保留小数秒）；锚点转换失败（时区表缺失）时整体为 NULL，
     * 由调用方的 COALESCE 兜底为原始值渲染。
     */
    protected static String tzShiftByAnchor(String quotedCol, String targetTimezone) {
        String anchor = "2020-06-01 12:00:00";
        String offsetSeconds = "TIMESTAMPDIFF(SECOND, CONVERT_TZ('" + anchor + "', @@session.time_zone, '"
                + targetTimezone + "'), '" + anchor + "')";
        return "DATE_ADD(" + quotedCol + ", INTERVAL -" + offsetSeconds + " SECOND)";
    }

}
