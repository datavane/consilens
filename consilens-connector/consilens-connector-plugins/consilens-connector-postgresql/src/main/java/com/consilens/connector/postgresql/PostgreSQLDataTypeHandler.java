package com.consilens.connector.postgresql;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

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
     * PostgreSQL-specific integer normalization: CAST to VARCHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     * Must match MySQL's behavior to ensure consistent hashing.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '0')";
    }
    /**
     * PostgreSQL-specific string normalization.
     * For CHAR types, CAST to VARCHAR to remove trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // CAST CHAR to VARCHAR to remove trailing spaces, then TRIM
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS VARCHAR)), '')";
    }
    
    /**
     * PostgreSQL-specific decimal normalization.
     * Use TO_CHAR to ensure decimal places with trailing zeros.
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
                return "COALESCE(TRIM(CAST(ROUND(" + quotedCol + ", 0) AS VARCHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNC(" + quotedCol + ", 0) AS VARCHAR)), '0')";
            }
        }
        
        String decimalPattern = "0".repeat(precision);
        String formatPattern = "FM999999999999990." + decimalPattern;
        String defaultValue = "0." + decimalPattern;
        
        if (rounding) {
            return "COALESCE(TO_CHAR(" + quotedCol + ", '" + formatPattern + "'), '" + defaultValue + "')";
        } else {
            // PostgreSQL TRUNC + TO_CHAR: truncate then format
            return "COALESCE(TO_CHAR(TRUNC(" + quotedCol + ", " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        }
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
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE PRECISION), 0) AS VARCHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNC(CAST(" + quotedCol + " AS DOUBLE PRECISION), 0) AS VARCHAR)), '0')";
            }
        }
        
        String decimalPattern = "0".repeat(precision);
        String formatPattern = "FM999999999999990." + decimalPattern;
        String defaultValue = "0." + decimalPattern;
        
        if (rounding) {
            return "COALESCE(TO_CHAR(CAST(" + quotedCol + " AS DOUBLE PRECISION), '" + formatPattern + "'), '" + defaultValue + "')";
        } else {
            // PostgreSQL TRUNC + TO_CHAR: truncate then format
            return "COALESCE(TO_CHAR(TRUNC(CAST(" + quotedCol + " AS DOUBLE PRECISION), " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        }
    }

    /**
     * PostgreSQL-specific date normalization: YYYY-MM-DD format.
     * DATE type has no timezone information, so no conversion needed.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'YYYY-MM-DD'), '')";
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
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'HH24:MI:SS'), '')";
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
        // DATETIME: No timezone conversion, format directly
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'YYYY-MM-DD HH24:MI:SS'), '')";
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
        // TIMESTAMP: Two-step timezone conversion using session timezone
        // Step 1: AT TIME ZONE current_setting('TIMEZONE') - interpret in session timezone, returns TIMESTAMPTZ
        // Step 2: AT TIME ZONE 'UTC' - convert to UTC, returns TIMESTAMP
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE current_setting('TIMEZONE') AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '')";
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
        // TIMESTAMPTZ: Direct conversion to UTC
        // AT TIME ZONE 'UTC' converts TIMESTAMPTZ to TIMESTAMP in UTC
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '')";
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
        return "CASE WHEN " + quotedCol + " = TRUE THEN '1' ELSE '0' END";
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
