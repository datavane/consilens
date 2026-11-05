package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-5：带偏移的时态值必须换算到 UTC 再渲染，否则 Java 侧（墙钟）与 SQL 侧（UTC）
 * 对同一时刻会给出不同字符串，流式比较出现假差异。
 */
@DisplayName("ValueNormalizer 时区口径")
class ValueNormalizerTemporalTest {

    @Test
    @DisplayName("OffsetDateTime 按 UTC 渲染而不是保留墙钟")
    void offsetDateTimeIsRenderedInUtc() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue(
                        OffsetDateTime.parse("2024-01-01T12:00:00+08:00"),
                        DataType.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    @DisplayName("ZonedDateTime 按 UTC 渲染")
    void zonedDateTimeIsRenderedInUtc() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue(
                        ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")),
                        DataType.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    @DisplayName("Instant 按 UTC 渲染")
    void instantIsRenderedInUtc() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue(Instant.parse("2024-01-01T04:00:00Z"),
                        DataType.TIMESTAMP));
    }

    @Test
    @DisplayName("无时区的 Timestamp 保持墙钟（会话时区未知，不做猜测）")
    void naiveTimestampKeepsWallClock() {
        assertEquals("2024-01-01 12:00:00",
                ValueNormalizer.normalizeValue(Timestamp.valueOf("2024-01-01 12:00:00"),
                        DataType.DATETIME));
    }

    @Test
    @DisplayName("带时区后缀的字符串仍换算到 UTC")
    void zonedStringIsConvertedToUtc() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue("2024-01-01 12:00:00 +08:00",
                        DataType.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    @DisplayName("ISO T 形式带偏移的字符串同样换算到 UTC")
    void isoOffsetStringIsConvertedToUtc() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue("2024-01-01T12:00:00+08:00",
                        DataType.TIMESTAMP_WITH_TIMEZONE));
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue("2024-01-01T12:00:00+0800",
                        DataType.TIMESTAMP_WITH_TIMEZONE));
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue("2024-01-01T04:00:00Z",
                        DataType.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    @DisplayName("配置会话时区后，无时区 Timestamp 按会话时区换算到 UTC")
    void naiveTimestampUsesConfiguredSessionZone() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue(Timestamp.valueOf("2024-01-01 12:00:00"),
                        DataType.DATETIME, ValueNormalizer.DEFAULT_DECIMAL_SCALE,
                        ZoneId.of("+08:00")));
    }

    @Test
    @DisplayName("LocalDateTime 同样按会话时区换算")
    void localDateTimeUsesConfiguredSessionZone() {
        assertEquals("2024-01-01 04:00:00",
                ValueNormalizer.normalizeValue(java.time.LocalDateTime.of(2024, 1, 1, 12, 0, 0),
                        DataType.DATETIME, ValueNormalizer.DEFAULT_DECIMAL_SCALE,
                        ZoneId.of("Asia/Shanghai")));
    }
}
