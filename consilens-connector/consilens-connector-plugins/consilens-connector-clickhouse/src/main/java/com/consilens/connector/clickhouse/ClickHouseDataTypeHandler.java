package com.consilens.connector.clickhouse;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.Map;

/**
 * ClickHouse data type handler.
 * 
 * <p>
 * Provides ClickHouse-specific data type normalization and conversion:
 * <ul>
 * <li>Column normalization for checksum calculation</li>
 * <li>Data type mapping from source to ClickHouse types</li>
 * <li>Timestamp formatting for ClickHouse</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class ClickHouseDataTypeHandler extends BaseDataTypeHandler {

    public ClickHouseDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new ClickHouse data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public ClickHouseDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * ClickHouse-specific string normalization.
     * Use trim to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(trim(" + quotedCol + "), '')";
    }

    /**
     * ClickHouse-specific integer normalization: CAST to String.
     * Use trim to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(trim(CAST(" + quotedCol + " AS String)), '0')";
    }

    /**
     * ClickHouse-specific decimal normalization with configurable decimal places.
     * Use formatDecimal to ensure specified decimal places with trailing zeros.
     * This ensures consistent decimal representation across databases.
     */
    /**
     * ClickHouse-specific decimal normalization with configurable decimal places.
     * Use formatDecimal to ensure specified decimal places with trailing zeros.
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
            if (rounding) {
                return "COALESCE(trim(CAST(round(" + quotedCol + ", 0) AS String)), '0')";
            } else {
                return "COALESCE(trim(CAST(truncate(" + quotedCol + ", 0) AS String)), '0')";
            }
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: round first, then format
            return "COALESCE(formatDecimal(round(" + quotedCol + ", " + precision + "), " + precision + "), '" + defaultValue + "')";
        } else {
            // Truncate: truncate first, then format
            return "COALESCE(formatDecimal(truncate(" + quotedCol + ", " + precision + "), " + precision + "), '" + defaultValue + "')";
        }
    }

    /**
     * ClickHouse-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to Float64 (DOUBLE) first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            if (rounding) {
                return "COALESCE(trim(CAST(round(CAST(" + quotedCol + " AS Float64), 0) AS String)), '0')";
            } else {
                return "COALESCE(trim(CAST(truncate(CAST(" + quotedCol + " AS Float64), 0) AS String)), '0')";
            }
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: round first, then format
            return "COALESCE(formatDecimal(round(CAST(" + quotedCol + " AS Float64), " + precision + "), " + precision + "), '" + defaultValue + "')";
        } else {
            // Truncate: truncate first, then format
            return "COALESCE(formatDecimal(truncate(CAST(" + quotedCol + " AS Float64), " + precision + "), " + precision + "), '" + defaultValue + "')";
        }
    }

    /**
     * ClickHouse-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(formatDateTime(" + quotedCol + ", '%Y-%m-%d'), '')";
    }

    /**
     * ClickHouse-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(formatDateTime(" + quotedCol + ", '%H:%M:%S'), '')";
    }

    /**
     * ClickHouse-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * For DATETIME type (no timezone information):
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(formatDateTime(" + quotedCol + ", '%Y-%m-%d %H:%M:%S'), '')";
    }

    /**
     * ClickHouse-specific timestamp normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     * 
     * For TIMESTAMP type (ClickHouse DateTime):
     * - Convert to UTC timezone before formatting
     * - This matches MySQL and PostgreSQL behavior
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", 'UTC'), '%Y-%m-%d %H:%M:%S'), '')";
    }

    /**
     * ClickHouse-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", 'UTC'), '%Y-%m-%d %H:%M:%S'), '')";
    }

    /**
     * ClickHouse-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * ClickHouse-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(upper(hex(" + quotedCol + ")), '')";
    }

    /**
     * ClickHouse-specific JSON normalization: convert to string.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS String), '')";
    }

    /**
     * ClickHouse-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(trim(CAST(" + quotedCol + " AS String)), '0')";
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null) {
            return DataType.UNKNOWN;
        }
        String type = sourceType.toLowerCase();

        switch (type) {
            case "int8":
            case "tinyint":
                return DataType.TINYINT;
            case "int16":
            case "smallint":
                return DataType.SMALLINT;
            case "int32":
            case "int":
            case "integer":
                return DataType.INTEGER;
            case "int64":
            case "bigint":
                return DataType.BIGINT;
            case "decimal":
            case "numeric":
                return DataType.DECIMAL;
            case "float32":
            case "float":
            case "real":
                return DataType.FLOAT;
            case "float64":
            case "double":
                return DataType.DOUBLE;
            case "string":
            case "varchar":
                return DataType.VARCHAR;
            case "fixedstring":
            case "char":
                return DataType.CHAR;
            case "text":
                return DataType.TEXT;
            case "date":
                return DataType.DATE;
            case "datetime":
            case "datetime64":
            case "timestamp":
                return DataType.TIMESTAMP;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "uuid":
                return DataType.VARCHAR;
            case "array":
            case "map":
            case "tuple":
                return DataType.TEXT;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "TEXT";
            case TINYINT:
                return "TINYINT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case FLOAT:
                return "REAL";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case DATE:
                return "DATE";
            case TIMESTAMP:
                return "TIMESTAMP";
            case BOOLEAN:
                return "BOOLEAN";
            case JSON:
                return "JSON";
            case JSONB:
                return "JSONB";
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
