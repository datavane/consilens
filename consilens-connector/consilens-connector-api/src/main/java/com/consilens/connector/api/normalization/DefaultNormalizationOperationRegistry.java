package com.consilens.connector.api.normalization;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DefaultNormalizationOperationRegistry implements NormalizationOperationRegistry {

    private final Map<String, NormalizationOperationDefinition> definitions;

    public DefaultNormalizationOperationRegistry() {
        this.definitions = createDefinitions();
    }

    @Override
    public Optional<NormalizationOperationDefinition> find(String operation) {
        return Optional.ofNullable(definitions.get(operation));
    }

    private Map<String, NormalizationOperationDefinition> createDefinitions() {
        Map<String, NormalizationOperationDefinition> result = new HashMap<>();
        result.put("normalize_string", definition("normalize_string",
                setOf("string"),
                setOf("trim", "nullValue")));
        result.put("format_number", definition("format_number",
                setOf("integer", "decimal", "float"),
                setOf("precision", "rounding", "tolerance", "relativeTolerance")));
        result.put("format_datetime", definition("format_datetime",
                setOf("date", "time", "time_with_timezone", "datetime", "timestamp", "timestamp_with_timezone"),
                // sessionTimeZone/session_time_zone 由 CompareExecutionSettings 读取，用来在两侧
                // 时区不同时对齐 TIMESTAMP 口径；这里不放行的话，文档里的写法会被校验直接拒绝。
                setOf("format", "timezone", "comparisonMode", "sessionTimeZone", "session_time_zone")));
        result.put("encode", definition("encode",
                setOf("binary"),
                setOf("format", "encoding", "uppercase")));
        result.put("map_boolean", definition("map_boolean",
                setOf("boolean"),
                setOf("trueValue", "falseValue", "nullValue")));
        return Collections.unmodifiableMap(result);
    }

    private NormalizationOperationDefinition definition(String name, Set<String> supportedTypes, Set<String> supportedParams) {
        return NormalizationOperationDefinition.builder()
                .name(name)
                .supportedTypes(supportedTypes)
                .supportedParams(supportedParams)
                .build();
    }

    private Set<String> setOf(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
