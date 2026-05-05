package com.consilens.cli.service;

import java.util.Locale;
import java.util.Map;

final class NormalizationTypeMapper {

    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("char", "string"),
            Map.entry("varchar", "string"),
            Map.entry("text", "string"),
            Map.entry("clob", "string"),
            Map.entry("longvarchar", "string"),
            Map.entry("string", "string"),
            Map.entry("tinyint", "integer"),
            Map.entry("smallint", "integer"),
            Map.entry("integer", "integer"),
            Map.entry("int", "integer"),
            Map.entry("bigint", "integer"),
            Map.entry("decimal", "decimal"),
            Map.entry("numeric", "decimal"),
            Map.entry("float", "float"),
            Map.entry("double", "float"),
            Map.entry("real", "float"),
            Map.entry("date", "date"),
            Map.entry("time", "time"),
            Map.entry("time with time zone", "time_with_timezone"),
            Map.entry("time with timezone", "time_with_timezone"),
            Map.entry("time without time zone", "time"),
            Map.entry("time without timezone", "time"),
            Map.entry("timetz", "time_with_timezone"),
            Map.entry("datetime", "datetime"),
            Map.entry("datetime2", "datetime"),
            Map.entry("timestamp", "timestamp"),
            Map.entry("timestamp with time zone", "timestamp_with_timezone"),
            Map.entry("timestamp with timezone", "timestamp_with_timezone"),
            Map.entry("timestamp without time zone", "timestamp"),
            Map.entry("timestamp without timezone", "timestamp"),
            Map.entry("timestamptz", "timestamp_with_timezone"),
            Map.entry("boolean", "boolean"),
            Map.entry("bool", "boolean"),
            Map.entry("bit", "boolean"),
            Map.entry("binary", "binary"),
            Map.entry("varbinary", "binary"),
            Map.entry("blob", "binary"),
            Map.entry("json", "json"),
            Map.entry("jsonb", "json")
    );

    private NormalizationTypeMapper() {
    }

    static String toMatchType(String type) {
        String normalized = normalize(type);
        if (normalized == null) {
            return null;
        }
        return TYPE_ALIASES.getOrDefault(normalized, normalized);
    }

    private static String normalize(String type) {
        if (type == null || type.trim().isEmpty()) {
            return null;
        }
        return type.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
    }
}
