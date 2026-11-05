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
import java.time.OffsetDateTime;
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

    /** JSON 规范形用：键排序 + 紧凑分隔符（与各方言 SQL 侧口径一致）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    // Standard date/time formatters (thread-safe)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 与 SQL 侧 normalizeDecimal 默认精度一致（normalization.decimal.precision 默认 4）。 */
    public static final int DEFAULT_DECIMAL_SCALE = 4;

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
        return normalizeValue(value, dataType, DEFAULT_DECIMAL_SCALE);
    }

    /**
     * 同 {@link #normalizeValue(Object, DataType)}，但定点精度由调用方给出：流式/本地路径
     * 从 normalization spec 读到 precision 后传进来，避免 SQL 侧按配置精度、Java 侧固定 4 位。
     */
    public static String normalizeValue(Object value, DataType dataType, int decimalScale) {
        return normalizeValue(value, dataType, decimalScale, null);
    }

    /**
     * 同 3 参版本，额外给出 JDBC 会话时区：无时区的 {@link java.sql.Timestamp} /
     * {@link LocalDateTime} 需要按会话时区解释墙钟再换算到 UTC，才能与 SQL 侧
     * （`CONVERT_TZ(@@session.time_zone,'+00:00')` / `AT TIME ZONE`）同形。
     * {@code sessionZone == null} 时保持旧行为（按墙钟渲染，不做换算）。
     */
    public static String normalizeValue(Object value, DataType dataType, int decimalScale,
                                        java.time.ZoneId sessionZone) {
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
                    return normalizeDecimal(value, decimalScale);

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
                case TIMESTAMP_WITH_TIMEZONE:
                    return normalizeDateTime(value, sessionZone);

                case BOOLEAN:
                case BIT:
                    return normalizeBoolean(value);

                case BLOB:
                case BINARY:
                case VARBINARY:
                case LONGBLOB:
                    // For binary types, return a consistent representation
                    return normalizeBinary(value);

                case JSON:
                case JSONB:
                    // 本地路径的 JSON 规范化：解析后键排序 + 紧凑序列化（设计 §2.5）。
                    // 缺失会让"仅键序/空格不同"在跨源本地比对时被误报。
                    return normalizeJson(value);

                case UUID:
                    // 统一小写：SQL Server 的 UNIQUEIDENTIFIER 读出为大写，文本形态的源为小写
                    return value == null ? "" : value.toString().trim().toLowerCase();

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
        // 不能用 longValue() 兜底：MySQL unsigned BIGINT / NUMBER(38) 会超过 Long.MAX_VALUE，
        // longValue() 会回绕，把两个不同的整数归一化成同一个值（静默判等）。
        if (value instanceof java.math.BigInteger) {
            return value.toString();
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return String.valueOf(((Number) value).longValue());
        }
        try {
            // BigDecimal.toBigInteger() 截断小数，与 longValue() 对浮点的处理一致
            return new BigDecimal(value.toString().trim()).toBigInteger().toString();
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
    /**
     * 主键分量归一化：定点数必须精确，不能用 {@link #normalizeDecimal} 的
     * double + 4 位小数形态（1.00001 与 1.00002 会碰撞，超大 DECIMAL 还会丢精度）。
     * 其余类型沿用 {@link #normalizeValue} 的既有口径。
     */
    public static String normalizeKey(Object value, DataType dataType) {
        if (value == null) {
            return "";
        }
        if (dataType == DataType.DECIMAL || dataType == DataType.NUMERIC) {
            BigDecimal decimal = toBigDecimal(value);
            if (decimal != null) {
                BigDecimal stripped = decimal.stripTrailingZeros();
                return stripped.signum() == 0 ? "0" : stripped.toPlainString();
            }
        }
        return normalizeValue(value, dataType);
    }

    private static BigDecimal toBigDecimal(Object value) {
        try {
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof java.math.BigInteger) {
                return new BigDecimal((java.math.BigInteger) value);
            }
            if (value instanceof Number) {
                return new BigDecimal(value.toString());
            }
            if (value instanceof String) {
                return new BigDecimal(((String) value).trim());
            }
        } catch (NumberFormatException exception) {
            log.warn("Failed to parse decimal key value: {}, error: {}", value, exception.getMessage());
        }
        return null;
    }

    private static String normalizeDecimal(Object value, int decimalScale) {
        // 走 BigDecimal 而不是 double：超大 DECIMAL（> 2^53）经 double 会丢精度，
        // 本地比较就会把两个不同的定点数判成相等/不等（与 SQL 侧 ROUND(x,4) 口径不符）。
        BigDecimal decimal = toBigDecimal(value);
        if (decimal == null) {
            log.warn("Failed to parse decimal value: {}", value);
            return "0.0000";
        }
        try {
            int scale = decimalScale < 0 ? DEFAULT_DECIMAL_SCALE : decimalScale;
            return decimal.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (ArithmeticException e) {
            log.warn("Failed to scale decimal value: {}, error: {}", value, e.getMessage());
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
                // 带时间部分的日期串（Oracle DATE 经 getString 形如 "2026-01-31 00:00:00"）：
                // 直接 LocalDate.parse 会失败并退化成 1970-01-01，两侧同值 → 日期差异被漏报。
                java.util.regex.Matcher datePrefix = java.util.regex.Pattern
                        .compile("^(\\d{4}-\\d{2}-\\d{2})[ T]").matcher(str);
                if (datePrefix.find()) {
                    return datePrefix.group(1);
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

    /** 把“会话时区下的墙钟”换算成 UTC 字符串；无会话时区时原样渲染。 */
    private static String formatWallClock(LocalDateTime wallClock, java.time.ZoneId sessionZone) {
        if (sessionZone == null) {
            return wallClock.format(DATETIME_FORMATTER);
        }
        return wallClock.atZone(sessionZone)
                .withZoneSameInstant(java.time.ZoneOffset.UTC)
                .toLocalDateTime()
                .format(DATETIME_FORMATTER);
    }

    /**
     * Normalize datetime/timestamp values: YYYY-MM-DD HH:MM:SS.
     * Handles various formats including ISO 8601 with timezone.
     */
    private static String normalizeDateTime(Object value) {
        return normalizeDateTime(value, null);
    }

    private static String normalizeDateTime(Object value, java.time.ZoneId sessionZone) {
        try {
            if (value instanceof LocalDateTime) {
                return formatWallClock((LocalDateTime) value, sessionZone);
            } else if (value instanceof java.time.OffsetDateTime
                    || value instanceof java.time.ZonedDateTime
                    || value instanceof java.time.Instant) {
                // 带偏移的值必须换算到 UTC 再渲染：Java 的 toString 只保留墙钟，
                // 直接格式化会把 12:00+08:00 渲染成 12:00（SQL 侧渲染的是 04:00 UTC）。
                java.time.Instant instant = value instanceof java.time.Instant
                        ? (java.time.Instant) value
                        : (value instanceof java.time.OffsetDateTime
                                ? ((java.time.OffsetDateTime) value).toInstant()
                                : ((java.time.ZonedDateTime) value).toInstant());
                return LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
                        .format(DATETIME_FORMATTER);
            } else if (value instanceof Timestamp) {
                // 无会话时区时按驱动给出的墙钟渲染（旧行为）；给了会话时区才换算到 UTC，
                // 因为 JDBC 的 Timestamp 本身不带时区信息。
                if (sessionZone != null) {
                    return formatWallClock(((Timestamp) value).toLocalDateTime(), sessionZone);
                }
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

                // Handle trailing zone name, e.g. Trino/Presto getString() on
                // TIMESTAMP WITH TIME ZONE: "2026-04-30 16:37:34 UTC". Convert
                // the instant to the canonical UTC wall clock so dialects that
                // render zone suffixes stay consistent with plain wall-clock
                // sources.
                java.util.regex.Matcher zoned = java.util.regex.Pattern
                        .compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)? ([A-Za-z/_0-9+\\-:]+)$")
                        .matcher(str);
                if (zoned.matches()) {
                    try {
                        LocalDateTime local = LocalDateTime.parse(zoned.group(1), DATETIME_FORMATTER);
                        // Oracle 驱动把偏移渲染成 "+0:00"（单数字小时），ZoneId.of 只认 "+00:00"
                        String zone = zoned.group(2).replaceAll("^([+-])(\\d):", "$10$2:");
                        return local.atZone(java.time.ZoneId.of(zone))
                                .withZoneSameInstant(java.time.ZoneOffset.UTC)
                                .toLocalDateTime().format(DATETIME_FORMATTER);
                    } catch (java.time.DateTimeException ignore) {
                        // ZoneRulesException is a subclass; not a resolvable zone id either
                    }
                }
                
                // Handle ISO 8601 format with timezone
                if (str.contains("T")) {
                    String iso = str.replaceAll("\\.\\d+", "");
                    // 带偏移的 ISO 时间必须先换算到 UTC，不能直接删掉偏移：
                    // "2024-01-01T12:00:00+08:00" 与 "2024-01-01 04:00:00Z" 是同一时刻。
                    java.util.regex.Matcher offset = java.util.regex.Pattern
                            .compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(Z|[+-]\\d{2}:?\\d{2})$")
                            .matcher(iso);
                    if (offset.matches()) {
                        try {
                            String zone = offset.group(2).replaceAll("^([+-]\\d{2})(\\d{2})$", "$1:$2");
                            return OffsetDateTime.parse(offset.group(1) + zone)
                                    .withOffsetSameInstant(ZoneOffset.UTC)
                                    .toLocalDateTime().format(DATETIME_FORMATTER);
                        } catch (java.time.DateTimeException ignore) {
                            // fall through: treat as local wall clock
                        }
                    }
                    // 带时区名的 ISO 形式（如 "...T16:37:34 UTC"）按同一规则换算
                    String spaced = iso.replace('T', ' ');
                    java.util.regex.Matcher zonedIso = java.util.regex.Pattern
                            .compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) ([A-Za-z/_0-9+\\-:]+)$")
                            .matcher(spaced);
                    if (zonedIso.matches()) {
                        try {
                            String zone = zonedIso.group(2).replaceAll("^([+-])(\\d):", "$10$2:");
                            return LocalDateTime.parse(zonedIso.group(1), DATETIME_FORMATTER)
                                    .atZone(java.time.ZoneId.of(zone))
                                    .withZoneSameInstant(ZoneOffset.UTC)
                                    .toLocalDateTime().format(DATETIME_FORMATTER);
                        } catch (java.time.DateTimeException ignore) {
                            // fall through to zone-less handling
                        }
                    }
                    if (spaced.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                        return spaced;
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
     * 对外暴露的 JSON 规范形：键排序 + 紧凑分隔符。SQL 侧 normalize_json、本地比较
     * （{@code LocalDiffEngine}）与流式比较共用同一实现，避免"一处排序、一处不排序"。
     */
    public static String canonicalJson(String text) {
        if (text == null) {
            return "";
        }
        return normalizeJson(text);
    }

    /**
     * Normalize JSON values: canonical form (sorted keys, compact separators).
     */
    private static String normalizeJson(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            Object parsed = JSON_MAPPER.readValue(text, Object.class);
            return JSON_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            log.debug("Failed to canonicalize JSON value: {}", e.getMessage());
            return text;
        }
    }

    /**
     * Normalize binary values: hexadecimal representation.
     */
    private static String normalizeBinary(Object value) {
        // 大写十六进制：与 DB 侧 HEX()（MySQL/Doris）及 RAWTOHEX（Oracle）等
        // 校验和路径的大写渲染保持一致；本地取数路径与 SQL 校验和路径必须产出
        // 同样的规范形式，否则同一行会因大小写差异被误报。
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        }
        // 二进制类型列读出的字符串值是引擎的 hex 渲染（如 Doris VARBINARY→
        // 小写 hex 文本）。统一大写，与 SQL 校验和路径的 HEX()/RAWTOHEX() 一致。
        if (value instanceof String) {
            return value.toString().trim().toUpperCase();
        }
        return value.toString();
    }
}
