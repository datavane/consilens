package com.consilens.cli.service;

import java.util.List;
import java.util.Locale;

public final class SensitiveValueMasker {

    private static final List<String> SENSITIVE_QUERY_KEYS = List.of(
            "password", "passwd", "pwd", "token", "secret", "user", "username", "key");

    private SensitiveValueMasker() {
    }

    public static String maskJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "(not set)";
        }
        String masked = maskSemicolonProperties(maskUserInfo(url));
        int queryStart = masked.indexOf('?');
        if (queryStart < 0 || queryStart == masked.length() - 1) {
            return masked;
        }
        String prefix = masked.substring(0, queryStart + 1);
        String query = masked.substring(queryStart + 1);
        String[] params = query.split("&", -1);
        for (int i = 0; i < params.length; i++) {
            int eq = params[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = params[i].substring(0, eq);
            if (isSensitiveQueryKey(key)) {
                params[i] = key + "=***";
            }
        }
        return prefix + String.join("&", params);
    }

    public static String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "(not set)";
        }
        String trimmed = username.trim();
        if (trimmed.length() <= 2) {
            return "***";
        }
        return trimmed.charAt(0) + "***" + trimmed.charAt(trimmed.length() - 1);
    }

    private static String maskUserInfo(String value) {
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return value;
        }
        int authorityStart = scheme + 3;
        int slash = value.indexOf('/', authorityStart);
        int query = value.indexOf('?', authorityStart);
        int authorityEnd;
        if (slash < 0) {
            authorityEnd = query >= 0 ? query : value.length();
        } else if (query < 0) {
            authorityEnd = slash;
        } else {
            authorityEnd = Math.min(slash, query);
        }
        int at = value.lastIndexOf('@', authorityEnd);
        if (at < authorityStart) {
            return value;
        }
        return value.substring(0, authorityStart) + "***@" + value.substring(at + 1);
    }

    private static boolean isSensitiveQueryKey(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        String compact = normalized.replace("_", "").replace("-", "").replace(".", "");
        return SENSITIVE_QUERY_KEYS.stream().anyMatch(normalized::equals)
                || compact.contains("password")
                || compact.contains("passwd")
                || compact.contains("token")
                || compact.contains("secret")
                || compact.equals("apikey")
                || compact.endsWith("apikey")
                || compact.endsWith("accesskey")
                || compact.endsWith("privatekey");
    }

    private static String maskSemicolonProperties(String value) {
        String[] parts = value.split(";", -1);
        if (parts.length <= 1) {
            return value;
        }
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = parts[i].substring(0, eq);
            if (isSensitiveQueryKey(key)) {
                parts[i] = key + "=***";
            }
        }
        return String.join(";", parts);
    }
}
