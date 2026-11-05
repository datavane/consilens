package com.consilens.sdk.verify;

import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads a JSONL diff-record file as a verification baseline and computes
 * difference identities used for NEW/FIXED/PERSISTING classification.
 *
 * <p>Identity is {@code operation | primaryKey | sortedChangedColumns}. The baseline
 * file must contain one JSON diff record per line, matching the shape written by the
 * run result artifact ({@code operation, primaryKey, metadata.changedColumns1/2}).
 */
public final class BaselineDiffReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BaselineDiffReader() {
    }

    public static Set<String> readIdentities(Path baselineFile) throws IOException {
        return new LinkedHashSet<>(readCounts(baselineFile).keySet());
    }

    /**
     * 读 baseline 的身份计数（multiset）。
     *
     * <p>重复键在 {@code validateUniqueKeys=false} 下是合法的，此时同一个身份会出现多次。
     * 只比集合会把"多出来的一行"当已存在，new 计成 0 —— 必须按出现次数比较。
     */
    public static Map<String, Integer> readCounts(Path baselineFile) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(baselineFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                counts.merge(identityOf(MAPPER.readTree(line)), 1, Integer::sum);
            }
        }
        return counts;
    }

    public static String identityOf(DiffRow row) {
        Set<String> changed = new TreeSet<>();
        if (row.getChangedColumns1() != null) {
            changed.addAll(row.getChangedColumns1());
        }
        if (row.getChangedColumns2() != null) {
            changed.addAll(row.getChangedColumns2());
        }
        return row.getOperation().getCode() + "|" + row.getPrimaryKey() + "|" + String.join(",", changed);
    }

    public static String identityOf(JsonNode node) {
        String operation = normalizeOperation(node.path("operation").asText("UNKNOWN"));
        List<Object> primaryKey = primaryKeyOf(node.get("primaryKey"));
        Set<String> changed = new TreeSet<>();
        JsonNode metadata = node.get("metadata");
        if (metadata != null && metadata.isObject()) {
            collect(metadata.get("changedColumns1"), changed);
            collect(metadata.get("changedColumns2"), changed);
        }
        return operation + "|" + primaryKey + "|" + String.join(",", changed);
    }

    /**
     * 主键既可能是数组（本仓库 JSONL sink 的产物），也可能是标量或对象（手写/外部产物）。
     * 标量包成单元素列表，避免一行畸形记录让整次校验以 IllegalArgumentException 崩掉。
     */
    private static List<Object> primaryKeyOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return MAPPER.convertValue(node,
                    MAPPER.getTypeFactory().constructParametricType(List.class, Object.class));
        }
        if (node.isValueNode()) {
            return List.of(MAPPER.convertValue(node, Object.class));
        }
        return List.of(MAPPER.convertValue(node, Map.class).toString());
    }

    /**
     * Normalizes both the JSONL wire code ({@code mismatch}) and legacy display
     * names ({@code MISMATCH}) to the same identity token.
     */
    private static String normalizeOperation(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "unknown";
        }
        for (DiffOperation operation : DiffOperation.values()) {
            if (operation.getCode().equals(normalized)) {
                return operation.getCode();
            }
        }
        return normalized;
    }

    private static void collect(JsonNode array, Set<String> target) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            if (item != null && !item.isNull()) {
                target.add(item.asText());
            }
        }
    }
}
