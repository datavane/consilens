package com.consilens.core.compare.jdbc.normalization;

import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LegacyNormalizationAdapter {

    private LegacyNormalizationAdapter() {
    }

    public static NormalizationSpec extractNormalizationSpec(Object value) {
        return value instanceof NormalizationSpec ? (NormalizationSpec) value : null;
    }

    public static Map<String, ?> toLegacyRuleMap(NormalizationSpec spec, String side) {
        if (spec == null) {
            return null;
        }
        Map<String, LegacyTypeNormalizationRule> result = new HashMap<>();
        applyRules(result, spec.getGlobal());
        if ("source".equalsIgnoreCase(side)) {
            applyRules(result, spec.getSource());
        } else if ("target".equalsIgnoreCase(side)) {
            applyRules(result, spec.getTarget());
        }
        return result.isEmpty() ? null : Collections.unmodifiableMap(result);
    }

    private static void applyRules(Map<String, LegacyTypeNormalizationRule> target, List<NormalizationRule> rules) {
        if (rules == null) {
            return;
        }
        for (NormalizationRule rule : rules) {
            if (rule == null || rule.getMatch() == null || rule.getMatch().getType() == null) {
                continue;
            }
            String type = rule.getMatch().getType();
            LegacyTypeNormalizationRule mapped = mapRule(rule);
            if (mapped != null) {
                target.put(type, mapped);
            }
        }
    }

    private static LegacyTypeNormalizationRule mapRule(NormalizationRule rule) {
        Map<String, Object> params = rule.getParams();
        if (params == null) {
            params = Collections.emptyMap();
        }

        LegacyTypeNormalizationRule mapped = new LegacyTypeNormalizationRule();
        String operation = rule.getOperation();
        if ("format_number".equals(operation)) {
            mapped.setPrecision(asInteger(params.get("precision")));
            mapped.setRounding(asBoolean(params.get("rounding")));
        } else if ("format_datetime".equals(operation)) {
            mapped.setFormat(asString(params.get("format")));
            mapped.setTimezone(asString(params.get("timezone")));
        } else if ("encode".equals(operation)) {
            mapped.setEncoding(firstString(params.get("encoding"), params.get("format")));
            mapped.setUppercase(asBoolean(params.get("uppercase")));
        } else if ("map_boolean".equals(operation)) {
            mapped.setTrueValue(asString(params.get("trueValue")));
            mapped.setFalseValue(asString(params.get("falseValue")));
            mapped.setNullValue(asString(params.get("nullValue")));
        } else if ("normalize_string".equals(operation)) {
            mapped.setNullValue(asString(params.get("nullValue")));
        } else {
            return null;
        }
        return mapped;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Integer ? (Integer) value : null;
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String firstString(Object... values) {
        for (Object value : values) {
            String stringValue = asString(value);
            if (stringValue != null) {
                return stringValue;
            }
        }
        return null;
    }
}
