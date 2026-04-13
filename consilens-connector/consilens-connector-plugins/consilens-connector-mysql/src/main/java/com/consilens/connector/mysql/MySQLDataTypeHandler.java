package com.consilens.connector.mysql;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

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
     * MySQL-specific integer normalization: CAST to CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '0')";
    }
    /**
     * MySQL-specific string normalization.
     * Use TRIM directly without CAST to avoid truncation issues.
     * MySQL's CAST(x AS CHAR) defaults to CHAR(1) which truncates to first character.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // Just TRIM, no CAST needed for MySQL
        return "COALESCE(TRIM(" + quotedCol + "), '')";
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
                return "COALESCE(TRIM(CAST(ROUND(" + quotedCol + ", 0) AS CHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(" + quotedCol + ", 0) AS CHAR)), '0')";
            }
        }
        
        String defaultValue = "0." + "0".repeat(precision);
        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(" + quotedCol + ", " + precision + "), ',', ''), '" + defaultValue + "')";
        } else {
            // MySQL TRUNCATE + FORMAT: truncate then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(" + quotedCol + ", " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
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
                return "COALESCE(TRIM(CAST(ROUND(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '0')";
            } else {
                return "COALESCE(TRIM(CAST(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '0')";
            }
        }
        
        String defaultValue = "0." + "0".repeat(precision);
        if (rounding) {
            return "COALESCE(REPLACE(FORMAT(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), ',', ''), '" + defaultValue + "')";
        } else {
            // MySQL TRUNCATE + FORMAT: truncate then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
        }
    }

    /**
     * MySQL-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '%Y-%m-%d'), '')";
    }

    /**
     * MySQL-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '%H:%i:%s'), '')";
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
        // DATETIME: Convert to UTC assuming it's in session timezone
        // This ensures consistency with PostgreSQL TIMESTAMPTZ
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
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
        // Same as normalizeDateTime for MySQL
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
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
        // MySQL TIMESTAMP is already timezone-aware, same as normalizeTimestamp
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * MySQL-specific boolean normalization: '0' or '1'.
     * MySQL BOOLEAN is actually TINYINT(1), so we need to handle it explicitly.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
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
        return "COALESCE(CAST(" + quotedCol + " AS CHAR), '')";
    }

    /**
     * MySQL-specific default normalization: CAST to CHAR instead of VARCHAR.
     * This handles BOOLEAN, ENUM, SET and other types.
     * BUGFIX: Use '0' as default value to match PostgreSQL for numeric types like UNSIGNED INT
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '0')";
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
