package com.consilens.connector.tidb;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.Map;

/**
 * TiDB data type handler.
 * 
 * <p>
 * Provides TiDB-specific data type normalization and conversion.
 * TiDB is highly compatible with MySQL, so this handler shares much logic with
 * MySQLDataTypeHandler.
 * </p>
 * 
 * @since 1.0.0
 */
public class TiDBDataTypeHandler extends BaseDataTypeHandler {

    public TiDBDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new TiDB data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public TiDBDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * TiDB-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        // Just TRIM, no CAST needed for TiDB (MySQL-compatible)
        return "COALESCE(TRIM(" + quotedCol + "), '')";
    }

    /**
     * TiDB-specific integer normalization: CAST to CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(CAST(" + quotedCol + " AS CHAR)), '0')";
    }

    /**
     * TiDB-specific decimal normalization with configurable decimal places.
     * Use FORMAT to ensure specified decimal places with trailing zeros, then remove commas.
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
            String roundFunction = rounding ? "ROUND" : "TRUNCATE";
            return "COALESCE(TRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS CHAR)), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: ROUND then format
            return "COALESCE(REPLACE(FORMAT(ROUND(" + quotedCol + ", " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
        } else {
            // Truncate: TRUNCATE then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(" + quotedCol + ", " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
        }
    }

    /**
     * TiDB-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNCATE";
            return "COALESCE(TRIM(CAST(" + roundFunction + "(CAST(" + quotedCol + " AS DOUBLE), 0) AS CHAR)), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: ROUND then format
            return "COALESCE(REPLACE(FORMAT(ROUND(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
        } else {
            // Truncate: TRUNCATE then format
            return "COALESCE(REPLACE(FORMAT(TRUNCATE(CAST(" + quotedCol + " AS DOUBLE), " + precision + "), " + precision + "), ',', ''), '" + defaultValue + "')";
        }
    }

    /**
     * TiDB-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(DATE_FORMAT(" + quotedCol + ", '%Y-%m-%d'), '')";
    }

    /**
     * TiDB-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TIME_FORMAT(" + quotedCol + ", '%H:%i:%s'), '')";
    }

    /**
     * TiDB-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For DATETIME type:
     * - TiDB DATETIME stores literal values without timezone information
     * - But when comparing with PostgreSQL TIMESTAMPTZ, we need to convert to UTC
     * - Assume the DATETIME value is in the session timezone
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        // DATETIME: Convert to UTC assuming it's in session timezone
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * TiDB-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // Same as normalizeDateTime for TiDB (MySQL-compatible)
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * TiDB-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // TiDB TIMESTAMP is already timezone-aware, same as normalizeTimestamp
        return "COALESCE(DATE_FORMAT(CONVERT_TZ(" + quotedCol + ", @@session.time_zone, '+00:00'), '%Y-%m-%d %H:%i:%s'), '')";
    }

    /**
     * TiDB-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * TiDB-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(UPPER(HEX(" + quotedCol + ")), '')";
    }

    /**
     * TiDB-specific JSON normalization: convert to CHAR.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS CHAR), '')";
    }

    /**
     * TiDB-specific default normalization.
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
            case "decimal":
            case "numeric":
            case "dec":
            case "fixed":
                return DataType.DECIMAL;
            case "float":
                return DataType.FLOAT;
            case "double":
            case "double precision":
            case "real":
                return DataType.DOUBLE;
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
            case "blob":
            case "mediumblob":
            case "longblob":
            case "tinyblob":
                return DataType.BLOB;
            case "json":
                return DataType.JSON;
            default:
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
