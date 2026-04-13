package com.consilens.connector.sqlserver;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.Map;

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

    /**
     * SQL Server-specific string normalization.
     * Use LTRIM+RTRIM to remove leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(" + quotedCol + ")), '')";
    }

    /**
     * SQL Server-specific integer normalization: CAST to VARCHAR(MAX).
     * Use LTRIM+RTRIM to ensure no leading/trailing spaces for cross-database consistency.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(CAST(" + quotedCol + " AS VARCHAR(MAX)))), '0')";
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
            return "COALESCE(LTRIM(RTRIM(CAST(" + roundFunction + "(" + quotedCol + ", 0) AS VARCHAR(MAX)))), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(LTRIM(STR(ROUND(" + quotedCol + ", " + precision + "), 38, " + precision + ")), '" + defaultValue + "')";
        } else {
            // Truncate: apply FLOOR then format (SQL Server has no TRUNCATE)
            return "COALESCE(LTRIM(STR(FLOOR(" + quotedCol + " * POWER(10, " + precision + ")) / POWER(10, " + precision + "), 38, " + precision + ")), '" + defaultValue + "')";
        }
    }

    /**
     * SQL Server-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT/REAL is single-precision and may have precision issues.
     * Cast to FLOAT (double precision in SQL Server) first to ensure consistent formatting.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("float", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            String roundFunction = rounding ? "ROUND" : "FLOOR";  // SQL Server has no TRUNCATE; use FLOOR instead
            return "COALESCE(LTRIM(RTRIM(CAST(" + roundFunction + "(CAST(" + quotedCol + " AS FLOAT), 0) AS VARCHAR(MAX)))), '0')";
        }

        String defaultValue = "0." + "0".repeat(precision);

        if (rounding) {
            // Round half up: apply ROUND then format
            return "COALESCE(LTRIM(STR(ROUND(CAST(" + quotedCol + " AS FLOAT), " + precision + "), 38, " + precision + ")), '" + defaultValue + "')";
        } else {
            // Truncate: apply FLOOR then format (SQL Server has no TRUNCATE)
            return "COALESCE(LTRIM(STR(FLOOR(CAST(" + quotedCol + " AS FLOAT) * POWER(10, " + precision + ")) / POWER(10, " + precision + "), 38, " + precision + ")), '" + defaultValue + "')";
        }
    }

    /**
     * SQL Server-specific date normalization: YYYY-MM-DD format.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        return "COALESCE(CONVERT(NVARCHAR, " + quotedCol + ", 23), '')";
    }

    /**
     * SQL Server-specific time normalization: HH:MM:SS format.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        return "COALESCE(CONVERT(NVARCHAR, " + quotedCol + ", 108), '')";
    }

    /**
     * SQL Server-specific datetime normalization: YYYY-MM-DD HH:MM:SS format.
     * For DATETIME type (no timezone information):
     * - No timezone conversion needed, just format directly
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(CONVERT(NVARCHAR, " + quotedCol + ", 120), '')";
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
        // Convert to UTC timezone, then format
        return "COALESCE(CONVERT(NVARCHAR, SWITCHOFFSET(" + quotedCol + ", '+00:00'), 120), '')";
    }

    /**
     * SQL Server-specific timestamp with timezone normalization: YYYY-MM-DD HH:MM:SS format.
     * CRITICAL: Convert to UTC timezone to ensure cross-database consistency.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        // Convert to UTC timezone, then format
        return "COALESCE(CONVERT(NVARCHAR, SWITCHOFFSET(" + quotedCol + ", '+00:00'), 120), '')";
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
        return "CASE WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * SQL Server-specific JSON normalization: convert to NVARCHAR(MAX).
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        return "COALESCE(CAST(" + quotedCol + " AS NVARCHAR(MAX)), '')";
    }

    /**
     * SQL Server-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(LTRIM(RTRIM(CAST(" + quotedCol + " AS VARCHAR(MAX)))), '0')";
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
