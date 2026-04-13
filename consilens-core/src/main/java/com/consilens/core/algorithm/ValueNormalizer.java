package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Normalizes values based on their data types for consistent comparison.
 * Ensures values are formatted uniformly regardless of database representation.
 */
@Slf4j
public class ValueNormalizer {

    // Standard date/time formatters (thread-safe)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ThreadLocal SimpleDateFormat instances for thread safety
    private static final ThreadLocal<SimpleDateFormat> LEGACY_DATE_FORMAT = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
    private static final ThreadLocal<SimpleDateFormat> LEGACY_TIME_FORMAT = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss"));
    private static final ThreadLocal<SimpleDateFormat> LEGACY_DATETIME_FORMAT = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    /**
     * Normalize value based on its data type.
     * 
     * @param value    The value to normalize
     * @param dataType The data type of the value
     * @return Normalized string representation
     */
    public static String normalizeValue(Object value, DataType dataType) {
        if (value == null) {
            return "";
        }

        try {
            // Normalize by delegating to type-specific helpers so that hash comparisons see a stable string.
            switch (dataType) {
                case VARCHAR:
                case CHAR:
                case TEXT:
                case CLOB:
                case LONGVARCHAR:
                    return normalizeString(value);

                case INTEGER:
                case BIGINT:
                case SMALLINT:
                case TINYINT:
                    return normalizeInteger(value);

                case DECIMAL:
                case NUMERIC:
                    return normalizeDecimal(value);

                case FLOAT:
                case DOUBLE:
                case REAL:
                    return normalizeFloatingPoint(value);

                case DATE:
                    return normalizeDate(value);

                case TIME:
                    return normalizeTime(value);

                case DATETIME:
                case TIMESTAMP:
                    return normalizeDateTime(value);

                case BOOLEAN:
                case BIT:
                    return normalizeBoolean(value);

                case BLOB:
                case BINARY:
                case VARBINARY:
                case LONGBLOB:
                    // For binary types, return a consistent representation
                    return normalizeBinary(value);

                default:
                    // Fallback: convert to string and trim
                    return value.toString().trim();
            }
        } catch (Exception e) {
            log.warn("Failed to normalize value {} with type {}: {}",
                    value.getClass().getSimpleName() + ":" + value.toString(),
                dataType, 
                e.getMessage());
            // Fallback to string representation
            return value.toString();
        }
    }

    /**
     * Normalize string values: TRIM and COALESCE.
     */
    private static String normalizeString(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    /**
     * Normalize integer values: no decimal point.
     */
    private static String normalizeInteger(Object value) {
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        try {
            return String.valueOf(Long.parseLong(value.toString().trim()));
        } catch (NumberFormatException e) {
            log.warn("Failed to parse integer value: {}, error: {}",
                    value.toString(),
                e.getMessage());
            return "0";
        }
    }

    /**
     * Normalize decimal values: 4 decimal places (0.0000).
     */
    private static String normalizeDecimal(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return String.format("%.4f", d);
        }
        try {
            double d = Double.parseDouble(value.toString().trim());
            return String.format("%.4f", d);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse decimal value: {}, error: {}",
                    value.toString(),
                e.getMessage());
            return "0.0000";
        }
    }

    /**
     * Normalize floating point values: reasonable precision.
     */
    private static String normalizeFloatingPoint(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            // Use scientific notation for very large/small numbers
            if (Math.abs(d) > 1e10 || (Math.abs(d) < 1e-10 && d != 0)) {
                return String.format("%.6e", d);
            }
            return String.format("%.6f", d);
        }
        try {
            double d = Double.parseDouble(value.toString().trim());
            if (Math.abs(d) > 1e10 || (Math.abs(d) < 1e-10 && d != 0)) {
                return String.format("%.6e", d);
            }
            return String.format("%.6f", d);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse floating point value: {}, error: {}",
                    value.toString(),
                e.getMessage());
            return "0.0";
        }
    }

    /**
     * Normalize date values: YYYY-MM-DD.
     */
    private static String normalizeDate(Object value) {
        try {
            if (value instanceof LocalDate) {
                return ((LocalDate) value).format(DATE_FORMATTER);
            } else if (value instanceof java.sql.Date) {
                return LEGACY_DATE_FORMAT.get().format((java.sql.Date) value);
            } else if (value instanceof Date) {
                return LEGACY_DATE_FORMAT.get().format((Date) value);
            } else if (value instanceof Timestamp) {
                return LEGACY_DATE_FORMAT.get().format(new Date(((Timestamp) value).getTime()));
            } else {
                // Try to parse as string
                String str = value.toString().trim();
                // If already in correct format, return as-is
                if (str.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return str;
                }
                // Otherwise try to parse and reformat
                LocalDate date = LocalDate.parse(str);
                return date.format(DATE_FORMATTER);
            }
        } catch (Exception e) {
            log.warn("Failed to normalize date value: {}, error: {}", 
                value != null ? value.getClass().getSimpleName() + ":" + value.toString() : "null", 
                e.getMessage());
            return "1970-01-01";
        }
    }

    /**
     * Normalize time values: HH:MM:SS.
     */
    private static String normalizeTime(Object value) {
        try {
            if (value instanceof LocalTime) {
                return ((LocalTime) value).format(TIME_FORMATTER);
            } else if (value instanceof Time) {
                return LEGACY_TIME_FORMAT.get().format((Time) value);
            } else if (value instanceof Date) {
                return LEGACY_TIME_FORMAT.get().format((Date) value);
            } else {
                // Try to parse as string
                String str = value.toString().trim();
                // If already in correct format, return as-is
                if (str.matches("\\d{2}:\\d{2}:\\d{2}")) {
                    return str;
                }
                LocalTime time = LocalTime.parse(str);
                return time.format(TIME_FORMATTER);
            }
        } catch (Exception e) {
            log.warn("Failed to normalize time value: {}, error: {}", 
                value != null ? value.getClass().getSimpleName() + ":" + value.toString() : "null", 
                e.getMessage());
            return "00:00:00";
        }
    }

    /**
     * Normalize datetime/timestamp values: YYYY-MM-DD HH:MM:SS.
     * Handles various formats including ISO 8601 with timezone.
     */
    private static String normalizeDateTime(Object value) {
        try {
            if (value instanceof LocalDateTime) {
                return ((LocalDateTime) value).format(DATETIME_FORMATTER);
            } else if (value instanceof Timestamp) {
                // Use SimpleDateFormat to format the timestamp
                // This preserves the time value as-is without timezone conversion
                // MySQL TIMESTAMP: JDBC returns the value in connection timezone
                // PostgreSQL TIMESTAMP: JDBC returns the literal value
                return LEGACY_DATETIME_FORMAT.get().format((Timestamp) value);
            } else if (value instanceof Date) {
                // Use SimpleDateFormat for Date as well
                return LEGACY_DATETIME_FORMAT.get().format((Date) value);
            } else {
                // Try to parse as string
                String str = value.toString().trim();
                
                // If already in correct format, return as-is
                if (str.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                    return str;
                }
                
                // Handle format with milliseconds: "2022-02-23 10:38:15.0" or "2022-02-23 10:38:15.123"
                if (str.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")) {
                    // Remove milliseconds
                    return str.substring(0, 19);
                }
                
                // Handle date only string
                if (str.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return str + " 00:00:00";
                }
                
                // Handle ISO 8601 format with timezone
                if (str.contains("T")) {
                    // Remove milliseconds first
                    str = str.replaceAll("\\.\\d+", "");
                    
                    // Remove timezone info
                    str = str.replaceAll("[+-]\\d{4}$", "")       // Remove +HHMM or -HHMM
                             .replaceAll("[+-]\\d{2}:\\d{2}$", "") // Remove +HH:MM or -HH:MM
                             .replaceAll("Z$", "");               // Remove Z
                    
                    // Replace 'T' with space
                    str = str.replace('T', ' ');
                    
                    // If now in correct format, return
                    if (str.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                        return str;
                    }
                }
                
                // Try to parse with LocalDateTime
                LocalDateTime dateTime = LocalDateTime.parse(str);
                return dateTime.format(DATETIME_FORMATTER);
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Failed to normalize datetime value: {}, error: {}", 
                value != null ? value.getClass().getSimpleName() + ":" + value.toString() : "null", 
                errorMsg);
            // Return the original string as fallback to avoid data loss
            return value != null ? value.toString() : "1970-01-01 00:00:00";
        }
    }

    /**
     * Normalize boolean values: '0' or '1'.
     */
    private static String normalizeBoolean(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0 ? "1" : "0";
        }
        String str = value.toString().trim().toLowerCase();
        return (str.equals("true") || str.equals("1") || str.equals("yes")) ? "1" : "0";
    }

    /**
     * Normalize binary values: hexadecimal representation.
     */
    private static String normalizeBinary(Object value) {
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        // Fallback to string representation
        return value.toString();
    }
}
