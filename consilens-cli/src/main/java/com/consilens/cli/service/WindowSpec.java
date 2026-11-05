package com.consilens.cli.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Incremental window applied to both sides as SQL predicates.
 *
 * <p>{@code from} is inclusive, {@code to} is exclusive. Source and target
 * column names may differ; they fall back to {@code column}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WindowSpec {

    private String column;
    private String sourceColumn;
    private String targetColumn;
    private String from;
    private String to;

    public boolean isEmpty() {
        return blank(column) && blank(sourceColumn) && blank(targetColumn)
                && blank(from) && blank(to);
    }

    public void validate() {
        if (blank(from) && blank(to)) {
            throw new IllegalArgumentException("window requires --window-from and/or --window-to");
        }
        String source = sourceColumn();
        String target = targetColumn();
        if (blank(source) || blank(target)) {
            throw new IllegalArgumentException("window requires --window-column"
                    + " (or both --source-window-column and --target-window-column)");
        }
    }

    /**
     * 增量窗口是否已被 watermark 整体覆盖（{@code from >= to}）。
     *
     * <p>两者是同一列上的时间字面量（来自 {@code --window-*} 或 checkpoint）。能解析成
     * {@link Temporal} 时按时间比较——字典序会把 {@code 2024-01-02T00:00:00} 判成小于
     * {@code 2024-01-02}（前者更长且前缀相同），从而漏判"已被覆盖"；解析不了才退回字典序。
     * 覆盖时本次比较没有任何行，必须显式跳过，否则"空窗口"会被当成"两侧数据完全一致"。
     */
    public boolean isAlreadyCovered() {
        if (blank(from) || blank(to)) {
            return false;
        }
        java.util.Optional<java.time.temporal.Temporal> lower = parseTemporal(from);
        java.util.Optional<java.time.temporal.Temporal> upper = parseTemporal(to);
        if (lower.isPresent() && upper.isPresent()) {
            Integer comparison = compare(lower.get(), upper.get());
            if (comparison != null) {
                return comparison >= 0;
            }
            // 一侧带时区、一侧不带：按 UTC 硬算会平移几个小时，可能把"还没比"的窗口判成已覆盖
            // （那等于永久漏掉差异）。判不了就一律"未覆盖"——多跑一次空窗口可以接受。
            return false;
        }
        return from.trim().compareTo(to.trim()) >= 0;
    }

    /** @return null 表示两者无法安全比较（例如一侧是本地时间、另一侧是带偏移的时间点）。 */
    private static Integer compare(java.time.temporal.Temporal left,
                                   java.time.temporal.Temporal right) {
        if (isZoned(left) != isZoned(right)) {
            return null;
        }
        if (isZoned(left)) {
            return toInstant(left).compareTo(toInstant(right));
        }
        // 都是"无时区"的本地时间：纯日期按当天零点展开后再比
        return naive(left).compareTo(naive(right));
    }

    private static boolean isZoned(java.time.temporal.Temporal temporal) {
        return temporal instanceof java.time.Instant
                || temporal instanceof java.time.OffsetDateTime;
    }

    private static java.time.LocalDateTime naive(java.time.temporal.Temporal temporal) {
        if (temporal instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) temporal).atStartOfDay();
        }
        if (temporal instanceof java.time.LocalDateTime) {
            return (java.time.LocalDateTime) temporal;
        }
        throw new IllegalArgumentException("unsupported temporal: " + temporal);
    }

    private static java.time.Instant toInstant(java.time.temporal.Temporal temporal) {
        if (temporal instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) temporal).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        }
        if (temporal instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) temporal).toInstant(java.time.ZoneOffset.UTC);
        }
        if (temporal instanceof java.time.Instant) {
            return (java.time.Instant) temporal;
        }
        return ((java.time.OffsetDateTime) temporal).toInstant();
    }

    /** 支持 {@code 2024-01-02}、{@code 2024-01-02T03:04:05}、{@code ...Z}、{@code ...+08:00}。 */
    private static java.util.Optional<java.time.temporal.Temporal> parseTemporal(String value) {
        String text = value.trim().replace(' ', 'T');
        try {
            if (text.length() == 10) {
                return java.util.Optional.of(java.time.LocalDate.parse(text));
            }
            if (text.endsWith("Z")) {
                return java.util.Optional.of(java.time.Instant.parse(text));
            }
            if (text.length() > 19 && (text.charAt(19) == '+' || text.charAt(19) == '-')) {
                return java.util.Optional.of(java.time.OffsetDateTime.parse(text));
            }
            return java.util.Optional.of(java.time.LocalDateTime.parse(text));
        } catch (java.time.format.DateTimeParseException ignore) {
            return java.util.Optional.empty();
        }
    }

    public String sourceColumn() {
        return blank(sourceColumn) ? column : sourceColumn;
    }

    public String targetColumn() {
        return blank(targetColumn) ? column : targetColumn;
    }

    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
