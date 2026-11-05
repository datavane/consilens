package com.consilens.cluster.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing helpers shared by cluster submitter implementations for the
 * portable {@code properties} map of a submission request.
 */
public final class ClusterSubmissionParameters {

    private static final Pattern MEMORY_PATTERN = Pattern.compile("^(\\d+)\\s*([bkmgt]?)$",
            Pattern.CASE_INSENSITIVE);

    private ClusterSubmissionParameters() {
    }

    public static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    public static String orDefault(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public static String required(Map<String, Object> params, String key, String name) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("cluster parameter is required: " + name);
        }
        return String.valueOf(value);
    }

    public static int intOrDefault(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    public static Integer positiveInteger(Object value, String name) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(String.valueOf(value));
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    /** 非堆内存预留（metaspace + 线程栈 + 直接缓冲区 + code cache）下限，MiB。 */
    private static final int NON_HEAP_RESERVE_MB = 160;
    /** 低于这个容器内存，堆保底 + 非堆预留就放不下了。 */
    private static final int MIN_CONTAINER_MEMORY_MB = NON_HEAP_RESERVE_MB + 96;

    /**
     * 容器内存（MiB）→ coordinator JVM 堆上限。
     *
     * <p>容器内存是**整进程**上限，堆之外还有 metaspace、线程栈、直接缓冲区与 code cache；
     * 直接把 {@code -Xmx} 设成容器上限会让 JVM 在堆将满时无法扩容元空间而 OOMKilled。
     * 取"容器内存的 70%"与"容器内存减去非堆预留"的较小值：小内存容器（如 256m）按 70% 算
     * 只留 77m，仍然不够非堆开销，必须让预留优先。
     */
    public static int coordinatorHeapMb(int containerMemoryMb) {
        // 保底堆 + 非堆预留已经超过容器上限时，任何取值都会被 OOMKilled：
        // 与其给一个必然失败的 -Xmx，不如在提交阶段就说清楚容器太小。
        if (containerMemoryMb < MIN_CONTAINER_MEMORY_MB) {
            throw new IllegalArgumentException("container memory must be at least "
                    + MIN_CONTAINER_MEMORY_MB + " MiB (got " + containerMemoryMb + " MiB): "
                    + "the coordinator needs " + NON_HEAP_RESERVE_MB + " MiB of non-heap plus a minimum heap");
        }
        int percentage = (int) Math.floor(containerMemoryMb * 0.7d);
        int reserved = containerMemoryMb - NON_HEAP_RESERVE_MB;
        return Math.max(64, Math.min(percentage, reserved));
    }

    /** Parses memory like {@code 1g}, {@code 2048m} or {@code 512k} into MiB. */
    public static int parseMemoryMb(String value) {
        Matcher matcher = MEMORY_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid memory format: " + value + " (expected e.g. 1g, 2048m)");
        }
        long amount = Long.parseLong(matcher.group(1));
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "":  return Math.toIntExact(amount);
            case "b": return Math.toIntExact(Math.max(1, amount / (1024 * 1024)));
            case "k": return Math.toIntExact(Math.max(1, amount / 1024));
            case "m": return Math.toIntExact(amount);
            case "g": return Math.toIntExact(amount * 1024);
            case "t": return Math.toIntExact(amount * 1024 * 1024);
            default:  throw new IllegalArgumentException("invalid memory format: " + value);
        }
    }

    /** Parses CPU cores like {@code 0.5} into millicores. */
    public static int parseCpuMilli(String value) {
        return (int) Math.round(Double.parseDouble(value.trim()) * 1000);
    }

    public static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    public static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return result;
    }

    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
