package com.consilens.connector.api.normalization;

import com.consilens.connector.api.ConnectorException;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DefaultNormalizationSpecValidator implements NormalizationSpecValidator {

    private static final Set<String> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            "string", "integer", "decimal", "float", "date", "time",
            "time_with_timezone", "datetime", "timestamp", "timestamp_with_timezone",
            "boolean", "binary", "json"));

    private static final Set<String> SUPPORTED_BINARY_FORMATS = new HashSet<>(Arrays.asList("hex", "base64"));
    private static final Set<String> SUPPORTED_TEMPORAL_COMPARISON_MODES = new HashSet<>(
            Arrays.asList("EXACT", "DATE_ONLY", "TRUNCATE_TO_SECOND", "TRUNCATE_TO_DAY"));

    private final NormalizationOperationRegistry operationRegistry;

    public DefaultNormalizationSpecValidator() {
        this(new DefaultNormalizationOperationRegistry());
    }

    public DefaultNormalizationSpecValidator(NormalizationOperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Override
    public void validate(NormalizationSpec spec) {
        if (spec == null) {
            return;
        }
        validateRules(spec.getGlobal(), "global");
        validateRules(spec.getSource(), "source");
        validateRules(spec.getTarget(), "target");
    }

    private void validateRules(List<NormalizationRule> rules, String scope) {
        if (rules == null) {
            return;
        }
        for (NormalizationRule rule : rules) {
            validateRule(rule, scope);
        }
    }

    private void validateRule(NormalizationRule rule, String scope) {
        if (rule == null) {
            throw new ConnectorException("Normalization rule in " + scope + " cannot be null");
        }
        MatchSpec match = rule.getMatch();
        if (match == null) {
            throw new ConnectorException("Normalization rule in " + scope + " must define match");
        }
        String type = match.getType();
        String path = match.getPath();
        if ((type == null || type.trim().isEmpty()) && (path == null || path.trim().isEmpty())) {
            throw new ConnectorException("Normalization rule in " + scope + " must define match.type or match.path");
        }
        if (type != null && !SUPPORTED_TYPES.contains(type)) {
            throw new ConnectorException("Unsupported normalization match.type '" + type + "' in " + scope);
        }
        if (path != null && !path.trim().isEmpty()) {
            throw new ConnectorException("Field-level normalization is reserved but not executable in current version: " + path);
        }

        String operation = rule.getOperation();
        if (operation == null || operation.trim().isEmpty()) {
            throw new ConnectorException("Normalization rule in " + scope + " must define operation");
        }

        NormalizationOperationDefinition definition = operationRegistry.find(operation)
                .orElseThrow(() -> new ConnectorException("Unsupported normalization operation '" + operation + "' in " + scope));

        if (type != null && !definition.getSupportedTypes().contains(type)) {
            throw new ConnectorException("Normalization operation '" + operation + "' does not support type '" + type + "' in " + scope);
        }

        validateParams(rule.getParams(), definition, scope, operation);
    }

    private void validateParams(Map<String, Object> params,
                                NormalizationOperationDefinition definition,
                                String scope,
                                String operation) {
        if (params == null || params.isEmpty()) {
            return;
        }

        for (String key : params.keySet()) {
            if (!definition.getSupportedParams().contains(key)) {
                throw new ConnectorException("Unsupported parameter '" + key + "' for operation '" + operation + "' in " + scope);
            }
        }

        if ("format_number".equals(operation)) {
            validateInteger(params.get("precision"), "precision", scope, operation, true);
            validateBoolean(params.get("rounding"), "rounding", scope, operation);
        } else if ("format_datetime".equals(operation)) {
            validateString(params.get("format"), "format", scope, operation, false);
            validateTimezone(params.get("timezone"), scope, operation);
            validateTemporalComparisonMode(params.get("comparisonMode"), scope, operation);
        } else if ("encode".equals(operation)) {
            Object encoding = params.get("encoding") != null ? params.get("encoding") : params.get("format");
            validateBinaryFormat(encoding, scope, operation);
            validateBoolean(params.get("uppercase"), "uppercase", scope, operation);
        } else if ("map_boolean".equals(operation)) {
            validateString(params.get("trueValue"), "trueValue", scope, operation, true);
            validateString(params.get("falseValue"), "falseValue", scope, operation, true);
            validateString(params.get("nullValue"), "nullValue", scope, operation, true);
        } else if ("normalize_string".equals(operation)) {
            validateBoolean(params.get("trim"), "trim", scope, operation);
            validateString(params.get("nullValue"), "nullValue", scope, operation, true);
        }
    }

    private void validateInteger(Object value, String key, String scope, String operation, boolean allowNull) {
        if (value == null && allowNull) {
            return;
        }
        if (!(value instanceof Integer)) {
            throw new ConnectorException("Parameter '" + key + "' for operation '" + operation + "' in " + scope + " must be Integer");
        }
        if (((Integer) value) < 0) {
            throw new ConnectorException("Parameter '" + key + "' for operation '" + operation + "' in " + scope + " must be >= 0");
        }
    }

    private void validateBoolean(Object value, String key, String scope, String operation) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Boolean)) {
            throw new ConnectorException("Parameter '" + key + "' for operation '" + operation + "' in " + scope + " must be Boolean");
        }
    }

    private void validateString(Object value, String key, String scope, String operation, boolean allowNull) {
        if (value == null && allowNull) {
            return;
        }
        if (!(value instanceof String)) {
            throw new ConnectorException("Parameter '" + key + "' for operation '" + operation + "' in " + scope + " must be String");
        }
        if (((String) value).trim().isEmpty()) {
            throw new ConnectorException("Parameter '" + key + "' for operation '" + operation + "' in " + scope + " must not be empty");
        }
    }

    private void validateTimezone(Object value, String scope, String operation) {
        if (value == null) {
            return;
        }
        validateString(value, "timezone", scope, operation, false);
        try {
            ZoneId.of((String) value);
        } catch (Exception e) {
            throw new ConnectorException("Invalid timezone '" + value + "' for operation '" + operation + "' in " + scope, e);
        }
    }

    private void validateTemporalComparisonMode(Object value, String scope, String operation) {
        if (value == null) {
            return;
        }
        validateString(value, "comparisonMode", scope, operation, false);
        String normalized = ((String) value).trim().toUpperCase();
        if (!SUPPORTED_TEMPORAL_COMPARISON_MODES.contains(normalized)) {
            throw new ConnectorException("Parameter 'comparisonMode' for operation '" + operation + "' in " + scope
                    + " must be one of " + SUPPORTED_TEMPORAL_COMPARISON_MODES);
        }
    }

    private void validateBinaryFormat(Object value, String scope, String operation) {
        if (value == null) {
            return;
        }
        validateString(value, "format", scope, operation, false);
        if (!SUPPORTED_BINARY_FORMATS.contains(((String) value))) {
            throw new ConnectorException("Parameter 'format' for operation '" + operation + "' in " + scope
                    + " must be one of " + SUPPORTED_BINARY_FORMATS);
        }
    }
}
