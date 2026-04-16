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
                setOf("precision", "rounding")));
        result.put("format_datetime", definition("format_datetime",
                setOf("date", "time", "datetime", "timestamp"),
                setOf("format", "timezone")));
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
