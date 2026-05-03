package com.consilens.conncetor.base;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.LegacyTypeMapper;
import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class BaseDataTypeHandler implements DataTypeHandler {

    private static final Set<String> DATE_ONLY_COMPARISON_MODES = Set.of("DATE_ONLY", "TRUNCATE_TO_DAY");
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

    protected String getFormat(String dataTypeName, String defaultFormat) {
        String format = getRuleValue(dataTypeName, "getFormat", String.class);
        return format != null ? format : defaultFormat;
    }

    protected String getTimezone(String dataTypeName, String defaultTimezone) {
        String timezone = getRuleValue(dataTypeName, "getTimezone", String.class);
        return timezone != null ? timezone : defaultTimezone;
    }

    protected String getComparisonMode(String dataTypeName, String defaultMode) {
        String comparisonMode = getRuleValue(dataTypeName, "getComparisonMode", String.class);
        return comparisonMode != null ? comparisonMode.trim().toUpperCase(Locale.ROOT) : defaultMode;
    }

    protected boolean isDateOnlyComparison(String dataTypeName) {
        return DATE_ONLY_COMPARISON_MODES.contains(getComparisonMode(dataTypeName, "EXACT"));
    }

    protected String escapeSqlLiteral(String value) {
        return value == null ? null : value.replace("'", "''");
    }

    private <T> T getRuleValue(String dataTypeName, String getterName, Class<T> valueType) {
        if (normalizationConfig == null) {
            return null;
        }

        try {
            Object rule = normalizationConfig.get(dataTypeName);
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

    @Override
    public String normalizeColumn(String columnName, DataType dataType) {
        String quotedCol = capabilityProvider.quote(columnName);

        // Log normalization at DEBUG level
        log.debug("BaseDataTypeHandler.normalizeColumn: column='{}', dataType={}", columnName, dataType);

        if (dataType == null || dataType == DataType.UNKNOWN) {
            log.warn("Unsupported data type normalization for column '{}', falling back to default normalization", columnName);
            return normalizeDefault(quotedCol);
        }
        
        switch (dataType) {
            case VARCHAR:
            case CHAR:
            case TEXT:
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
                log.debug("  -> Using normalizeFloat");
                return normalizeFloat(quotedCol);

            case DECIMAL:
            case DOUBLE:
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

            default:
                log.warn("Unsupported data type normalization for column '{}': {}, falling back to default normalization",
                        columnName, dataType);
                return normalizeDefault(quotedCol);
        }
    }

    /**
     * Normalize string types (VARCHAR, CHAR, TEXT).
     * Default: TRIM and convert NULL to empty string.
     */
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), '')";
    }

    /**
     * Normalize integer types (TINYINT, SMALLINT, INTEGER, BIGINT).
     * Default: Convert to string, NULL becomes '0'.
     */
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '0')";
    }

    /**
     * Normalize decimal types (DECIMAL, FLOAT, DOUBLE, REAL).
     * Default: Format with 4 decimal places, NULL becomes '0.0000'.
     * Subclasses should override with database-specific formatting.
     */
    protected String normalizeDecimal(String quotedCol) {
        // Generic fallback - databases should override this
        return "COALESCE(CAST(" + quotedCol + " AS VARCHAR), '0.0000')";
    }

    /**
     * Normalize float type (single-precision floating point).
     * Default: Cast to DOUBLE first to avoid precision loss, then format.
     * Subclasses should override with database-specific formatting.
     * 
     * Note: FLOAT is a single-precision type that may have precision issues.
     * Converting to DOUBLE first helps maintain consistency.
     */
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
        return "CASE WHEN " + quotedCol + " = TRUE THEN '1' ELSE '0' END";
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

}
