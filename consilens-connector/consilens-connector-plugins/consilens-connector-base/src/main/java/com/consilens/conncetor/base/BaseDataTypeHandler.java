package com.consilens.conncetor.base;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class BaseDataTypeHandler implements DataTypeHandler {

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
        if (normalizationConfig == null) {
            return defaultPrecision;
        }
        
        try {
            Object rule = normalizationConfig.get(dataTypeName);
            if (rule != null) {
                // Get precision field via reflection
                java.lang.reflect.Method getPrecisionMethod = rule.getClass().getMethod("getPrecision");
                Integer precision = (Integer) getPrecisionMethod.invoke(rule);
                if (precision != null) {
                    return precision;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get precision config for type '{}': {}", dataTypeName, e.getMessage());
        }
        
        return defaultPrecision;
    }

    /**
     * Returns the configured rounding flag for the given data type.
     *
     * @param dataTypeName data type name in lowercase (e.g. "decimal", "float")
     * @param defaultRounding fallback rounding flag when no config is present
     * @return configured rounding flag, or defaultRounding if not set
     */
    protected boolean getRounding(String dataTypeName, boolean defaultRounding) {
        if (normalizationConfig == null) {
            return defaultRounding;
        }
        
        try {
            Object rule = normalizationConfig.get(dataTypeName);
            if (rule != null) {
                // Get rounding field via reflection
                Method getRoundingMethod = rule.getClass().getMethod("getRounding");
                Boolean rounding = (Boolean) getRoundingMethod.invoke(rule);
                if (rounding != null) {
                    return rounding;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get rounding config for type '{}': {}", dataTypeName, e.getMessage());
        }
        
        return defaultRounding;
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

    private String canonicalizeTypeName(String sourceType) {
        String upperType = sourceType.trim().toUpperCase(Locale.ROOT);
        upperType = upperType.replaceAll("\\s*\\([^)]*\\)", "");
        upperType = upperType.replaceAll("\\s+", " ").trim();
        return upperType.replace(' ', '_');
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

}
