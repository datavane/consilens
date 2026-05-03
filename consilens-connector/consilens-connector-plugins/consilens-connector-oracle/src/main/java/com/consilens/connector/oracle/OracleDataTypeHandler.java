package com.consilens.connector.oracle;

import com.consilens.common.type.TypeDescriptor;
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
                        if (precision <= 18) {
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
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE).originType(originType).build();
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
