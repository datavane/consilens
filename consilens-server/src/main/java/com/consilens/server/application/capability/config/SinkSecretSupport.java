package com.consilens.server.application.capability.config;

import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 表 sink 的 JDBC 密码与 source/target 连接密码同等对待：
 * 落库前加密、API 回显掩码、执行前解密。
 *
 * <p>{@code SinkConfig.properties} 既可能是 Map（UI/内存配置）也可能是 JSON 字符串
 * （反序列化后的存储形态），两种形态都要覆盖。
 */
public final class SinkSecretSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SinkSecretSupport() {
    }

    /** 直接改写入参 config 的 result.sinks[].properties.password。 */
    @SuppressWarnings("unchecked")
    public static void transformConfigSinkPasswords(Map<String, Object> config,
                                                    Function<String, String> passwordTransform) {
        if (config == null) {
            return;
        }
        Object result = config.get("result");
        if (!(result instanceof Map)) {
            return;
        }
        Object sinks = ((Map<String, Object>) result).get("sinks");
        if (!(sinks instanceof List)) {
            return;
        }
        for (Object item : (List<?>) sinks) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> sink = (Map<String, Object>) item;
            Object properties = sink.get("properties");
            if (properties instanceof Map) {
                transformProperties((Map<String, Object>) properties, passwordTransform);
            } else if (properties instanceof String && !((String) properties).isBlank()) {
                sink.put("properties", transformPropertiesJson((String) properties, passwordTransform));
            }
        }
    }

    /** 直接改写每个 SinkConfig.properties 中的 password。 */
    public static void transformSinkConfigs(List<SinkConfig> sinks,
                                            Function<String, String> passwordTransform) {
        if (sinks == null || sinks.isEmpty()) {
            return;
        }
        for (SinkConfig sink : sinks) {
            if (sink == null || sink.getProperties() == null || sink.getProperties().isBlank()) {
                continue;
            }
            sink.setProperties(transformPropertiesJson(sink.getProperties(), passwordTransform));
        }
    }

    private static void transformProperties(Map<String, Object> properties,
                                            Function<String, String> passwordTransform) {
        Object password = properties.get("password");
        if (!(password instanceof String)) {
            return;
        }
        String transformed = passwordTransform.apply((String) password);
        if (transformed == null || transformed.isEmpty()) {
            properties.remove("password");
        } else {
            properties.put("password", transformed);
        }
    }

    private static String transformPropertiesJson(String json,
                                                  Function<String, String> passwordTransform) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!(node instanceof ObjectNode)) {
                return json;
            }
            ObjectNode object = (ObjectNode) node;
            JsonNode password = object.get("password");
            if (password == null || !password.isTextual()) {
                return json;
            }
            String transformed = passwordTransform.apply(password.asText());
            if (transformed == null || transformed.isEmpty()) {
                object.remove("password");
            } else {
                object.put("password", transformed);
            }
            return MAPPER.writeValueAsString(object);
        } catch (Exception exception) {
            // properties 本身不是合法 JSON 时保持原样，由 sink open 阶段报配置错误；
            // 不能在这里静默丢弃整个 properties（会连 url/username 一起丢失）。
            return json;
        }
    }

    /**
     * API 编辑语义：GET 返回的 mask 原样 PUT 回来时，用旧配置里的密文回填，
     * 避免“只是改个名字”就把 sink 密码清空。
     */
    @SuppressWarnings("unchecked")
    public static void restoreMaskedPasswords(Map<String, Object> target,
                                              Map<String, Object> existing) {
        List<Map<String, Object>> existingSinks = sinks(existing);
        for (Map<String, Object> targetSink : sinks(target)) {
            Map<String, Object> existingSink = matchingSink(targetSink, existingSinks);
            if (existingSink == null) {
                continue;
            }
            Object targetProperties = targetSink.get("properties");
            Object existingProperties = existingSink.get("properties");
            String targetPassword = passwordOf(targetProperties);
            String existingPassword = passwordOf(existingProperties);
            if (targetPassword == null || !targetPassword.matches("\\*+")
                    || existingPassword == null || existingPassword.isBlank()) {
                continue;
            }
            if (targetProperties instanceof Map) {
                ((Map<String, Object>) targetProperties).put("password", existingPassword);
            } else if (targetProperties instanceof String) {
                targetSink.put("properties",
                        putPasswordJson((String) targetProperties, existingPassword));
            }
        }
    }

    /**
     * 按 sink 身份（format/type/url/tableName）配对，而不是数组下标：
     * 用户重排或插入 sink 时，下标回填会把 A 的密码写到 B 上。
     */
    private static Map<String, Object> matchingSink(Map<String, Object> target,
                                                    List<Map<String, Object>> existingSinks) {
        for (Map<String, Object> existing : existingSinks) {
            if (sameIdentity(target, existing)) {
                return existing;
            }
        }
        return null;
    }

    private static boolean sameIdentity(Map<String, Object> left, Map<String, Object> right) {
        if (!java.util.Objects.equals(text(left.get("format")), text(right.get("format")))
                || !java.util.Objects.equals(text(left.get("type")), text(right.get("type")))) {
            return false;
        }
        String leftUrl = propertyOf(left.get("properties"), "url");
        String rightUrl = propertyOf(right.get("properties"), "url");
        if (leftUrl != null && rightUrl != null && !leftUrl.equals(rightUrl)) {
            return false;
        }
        String leftTable = propertyOf(left.get("properties"), "tableName");
        String rightTable = propertyOf(right.get("properties"), "tableName");
        return leftTable == null || rightTable == null || leftTable.equals(rightTable);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String propertyOf(Object properties, String key) {
        if (properties instanceof Map) {
            Object value = ((Map<?, ?>) properties).get(key);
            return value instanceof String ? (String) value : null;
        }
        if (!(properties instanceof String) || ((String) properties).isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree((String) properties);
            JsonNode value = node.get(key);
            return value != null && value.isTextual() ? value.asText() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sinks(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object result = config.get("result");
        if (!(result instanceof Map)) {
            return List.of();
        }
        Object sinks = ((Map<String, Object>) result).get("sinks");
        if (!(sinks instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> values = new java.util.ArrayList<>();
        for (Object item : (List<?>) sinks) {
            if (item instanceof Map) {
                values.add((Map<String, Object>) item);
            }
        }
        return values;
    }

    private static String passwordOf(Object properties) {
        if (properties instanceof Map) {
            Object password = ((Map<?, ?>) properties).get("password");
            return password instanceof String ? (String) password : null;
        }
        if (!(properties instanceof String) || ((String) properties).isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree((String) properties);
            JsonNode password = node.get("password");
            return password != null && password.isTextual() ? password.asText() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String putPasswordJson(String json, String password) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!(node instanceof ObjectNode)) {
                return json;
            }
            ((ObjectNode) node).put("password", password);
            return MAPPER.writeValueAsString(node);
        } catch (Exception exception) {
            return json;
        }
    }
}
