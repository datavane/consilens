package com.consilens.connector.oracle;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.Map;

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
     * Oracle-specific string normalization.
     * Use TRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(TRIM(" + quotedCol + "), '')";
    }

    /**
     * Oracle-specific integer normalization: TO_CHAR.
     * Use TRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(TRIM(TO_CHAR(" + quotedCol + ")), '0')";
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
            return "COALESCE(TRIM(TO_CHAR(" + roundFunction + "(" + quotedCol + ", 0), 'FM999999999999990')), '0')";
        }

        String formatPattern = "FM999999999999990." + "0".repeat(precision);
        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(TO_CHAR(ROUND(" + quotedCol + ", " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        } else {
            // Truncate: apply TRUNC then format
            return "COALESCE(TO_CHAR(TRUNC(" + quotedCol + ", " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        }
    }

    /**
     * Oracle-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to BINARY_DOUBLE first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "TRUNC";
            return "COALESCE(TRIM(TO_CHAR(" + roundFunction + "(CAST(" + quotedCol + " AS BINARY_DOUBLE), 0), 'FM999999999999990')), '0')";
        }

        String formatPattern = "FM999999999999990." + "0".repeat(precision);
        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(TO_CHAR(ROUND(CAST(" + quotedCol + " AS BINARY_DOUBLE), " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        } else {
            // Truncate: apply TRUNC then format
            return "COALESCE(TO_CHAR(TRUNC(CAST(" + quotedCol + " AS BINARY_DOUBLE), " + precision + "), '" + formatPattern + "'), '" + defaultValue + "')";
        }
    }

    /**
     * Oracle-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'YYYY-MM-DD'), '')";
    }

    /**
     * Oracle-specific time normalization: HH24:MI:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'HH24:MI:SS'), '')";
    }

    /**
     * Oracle-specific datetime normalization: YYYY-MM-DD HH24:MI:SS format.
     * For DATE type (Oracle DATE contains time):
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(TO_CHAR(" + quotedCol + ", 'YYYY-MM-DD HH24:MI:SS'), '')";
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
        // Convert to UTC timezone, then format
        return "COALESCE(TO_CHAR(CAST(" + quotedCol + " AS TIMESTAMP WITH TIME ZONE) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '')";
    }

    /**
     * Oracle-specific timestamp with timezone normalization: YYYY-MM-DD HH24:MI:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(TO_CHAR(" + quotedCol + " AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '')";
    }

    /**
     * Oracle-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(RAWTOHEX(" + quotedCol + "), '')";
    }

    /**
     * Oracle-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
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
        return "COALESCE(TRIM(TO_CHAR(" + quotedCol + ")), '0')";
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
                // Oracle DATE contains time, so map to TIMESTAMP to preserve it
                return DataType.TIMESTAMP;
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
            case "blob":
            case "raw":
            case "long raw":
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
