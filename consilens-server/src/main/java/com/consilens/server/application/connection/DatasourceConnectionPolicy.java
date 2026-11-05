package com.consilens.server.application.connection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for datasource connection parameters: which keys may
 * reach the JDBC URL, which characters are safe, and which properties keys are
 * forbidden. Used by create/update, connection test, run-time injection and
 * transient metadata so no path diverges on whitelists.
 */
public final class DatasourceConnectionPolicy {

    /** Options keys forwarded into dialect JDBC URL params (never host/port/db/user/password). */
    public static final Set<String> OPTION_WHITELIST = Set.of("sid", "schema", "properties");

    /** 名称类字段(host/database/sid/schema)允许的字符,防止非法值注入 JDBC URL。 */
    public static final String NAME_CHARS_PATTERN = "[A-Za-z0-9_.$: \\-]{1,256}";

    /** 连接参数串(properties)允许的字符,形如 key=value&key2=value2。 */
    public static final String PROPERTIES_PATTERN = "[A-Za-z0-9_=.,&:\\- ]{0,1024}";

    /** properties 中禁止携带的敏感 JDBC 连接键（身份/超时由系统接管）。 */
    public static final Set<String> SENSITIVE_PROPERTY_KEYS =
            Set.of("user", "password", "connecttimeout", "logintimeout");

    private DatasourceConnectionPolicy() {
    }

    /**
     * Validates a datasource param map: host required and character-safe,
     * database/sid/schema name-safe, port in range, properties pattern-safe
     * and free of sensitive keys.
     */
    public static void validateParam(Map<String, Object> param) {
        Object host = param.get("host");
        if (host == null || String.valueOf(host).isBlank()) {
            throw new IllegalArgumentException("param.host is required");
        }
        if (!String.valueOf(host).matches("[A-Za-z0-9._:\\-]{1,128}")) {
            throw new IllegalArgumentException("param.host contains invalid characters");
        }
        validateNameField(param, "database");
        validateNameField(param, "sid");
        validateNameField(param, "schema");
        Object port = param.get("port");
        if (port != null) {
            Integer portValue = integerValue(port);
            if (portValue == null || portValue < 1 || portValue > 65535) {
                throw new IllegalArgumentException("param.port is invalid");
            }
        }
        Object properties = param.get("properties");
        if (properties != null && !String.valueOf(properties).isBlank()) {
            validateProperties(String.valueOf(properties));
        }
    }

    /** Validates a properties string for characters and forbidden sensitive keys. */
    public static void validateProperties(String propertiesText) {
        if (!propertiesText.matches(PROPERTIES_PATTERN)) {
            throw new IllegalArgumentException("param.properties contains invalid characters");
        }
        for (String pair : propertiesText.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator > 0 ? pair.substring(0, separator).trim() : pair.trim();
            if (isSensitivePropertyKey(key)) {
                throw new IllegalArgumentException("param.properties contains sensitive key: " + key);
            }
        }
    }

    /** Extracts whitelisted options (sid/schema/properties) from a request options map. */
    public static Map<String, Object> whitelistedOptions(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (!OPTION_WHITELIST.contains(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                continue;
            }
            if (("sid".equals(entry.getKey()) || "schema".equals(entry.getKey()))
                    && !text.matches(NAME_CHARS_PATTERN)) {
                throw new IllegalArgumentException("options." + entry.getKey() + " contains invalid characters");
            }
            if ("properties".equals(entry.getKey())) {
                validateProperties(text);
            }
            filtered.put(entry.getKey(), value);
        }
        return filtered.isEmpty() ? null : filtered;
    }

    public static boolean isSensitivePropertyKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        // 驱动属性里大量凭据并不叫 password：sslpassword / trustStorePassword /
        // clientSecret / accessToken 等，同样不能明文落库或回显。
        return SENSITIVE_PROPERTY_KEYS.contains(normalized)
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.equals("credential")
                || normalized.endsWith("credentials")
                || normalized.equals("token")
                || normalized.endsWith("token")
                || normalized.equals("apikey")
                || normalized.equals("api_key");
    }

    private static void validateNameField(Map<String, Object> param, String field) {
        Object value = param.get(field);
        if (value != null && !String.valueOf(value).isBlank()
                && !String.valueOf(value).matches(NAME_CHARS_PATTERN)) {
            throw new IllegalArgumentException("param." + field + " contains invalid characters");
        }
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
