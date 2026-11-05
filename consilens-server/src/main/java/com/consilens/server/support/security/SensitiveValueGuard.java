package com.consilens.server.support.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recursively detects fields that must never be logged or persisted in clear
 * text. Matching is case-insensitive on the field name, not on the value.
 */
public final class SensitiveValueGuard {

    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "password", "passwd", "pwd", "secret", "token",
            "apikey", "accesskey", "privatekey", "connection.password");

    private SensitiveValueGuard() {
    }

    public static List<String> findSensitivePaths(Map<String, ?> map) {
        List<String> paths = new ArrayList<>();
        scanMap(map, "", paths);
        return paths;
    }

    public static List<String> findSensitivePaths(JsonNode node) {
        List<String> paths = new ArrayList<>();
        scanJson(node, "", paths);
        return paths;
    }

    public static boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN_NAMES.contains(normalized);
    }

    public static JsonNode redact(JsonNode node) {
        return redact(node, new ObjectMapper());
    }

    public static JsonNode redact(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (isSensitiveName(entry.getKey())) {
                    result.put(entry.getKey(), "***");
                } else {
                    result.set(entry.getKey(), redact(entry.getValue(), mapper));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(item -> result.add(redact(item, mapper)));
            return result;
        }
        return node;
    }

    private static void scanMap(Map<?, ?> map, String path, List<String> paths) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (isSensitiveName(key)) {
                paths.add(childPath);
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                scanMap((Map<?, ?>) value, childPath, paths);
            } else if (value instanceof JsonNode) {
                scanJson((JsonNode) value, childPath, paths);
            }
        }
    }

    private static void scanJson(JsonNode node, String path, List<String> paths) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (isSensitiveName(entry.getKey())) {
                    paths.add(childPath);
                } else {
                    scanJson(entry.getValue(), childPath, paths);
                }
            });
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                scanJson(item, path + "[" + index + "]", paths);
                index++;
            }
        }
    }
}
