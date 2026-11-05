package com.consilens.connector.api.normalization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizationSpecsTest {

    @Test
    void acceptsSessionTimeZoneOnFormatDatetimeRules() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("format_datetime", Map.of(
                        "format", "yyyy-MM-dd HH:mm:ss",
                        "sessionTimeZone", "+08:00"))))
                .build();

        // The documented sessionTimeZone knob must pass validation; otherwise
        // CompareExecutionSettings can never read it from a rule.
        new DefaultNormalizationSpecValidator().validate(spec);
    }

    @Test
    void rejectsUnknownFormatDatetimeParameter() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("format_datetime", Map.of(
                        "format", "yyyy-MM-dd HH:mm:ss",
                        "sesionTimeZone", "+08:00"))))
                .build();

        assertThrows(com.consilens.connector.api.ConnectorException.class,
                () -> new DefaultNormalizationSpecValidator().validate(spec));
    }

    private NormalizationRule rule(String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().type("timestamp").build())
                .operation(operation)
                .params(params)
                .build();
    }

    @Test
    void mapsDecimalPrecisionToFormatNumberRule() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("numeric", Map.of("precision", 6, "rounding", true)),
                null, null);

        assertEquals(1, spec.getGlobal().size());
        NormalizationRule rule = spec.getGlobal().get(0);
        assertEquals("decimal", rule.getMatch().getType());
        assertEquals("format_number", rule.getOperation());
        assertEquals(6, rule.getParams().get("precision"));
        assertEquals(true, rule.getParams().get("rounding"));
    }

    @Test
    void mapsTemporalAndEncodingAndBooleanRules() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(null,
                Map.of(
                        "timestamp with time zone", Map.of("timezone", "UTC", "comparisonMode", "instant"),
                        "varbinary", Map.of("encoding", "hex", "uppercase", true),
                        "bool", Map.of("trueValue", "1", "falseValue", "0", "nullValue", "NULL")),
                null);

        List<NormalizationRule> source = spec.getSource();
        assertEquals(3, source.size());
        NormalizationRule temporal = ruleOfType(source, "timestamp_with_timezone");
        assertEquals("format_datetime", temporal.getOperation());
        assertEquals("UTC", temporal.getParams().get("timezone"));
        NormalizationRule binary = ruleOfType(source, "binary");
        assertEquals("encode", binary.getOperation());
        assertEquals("hex", binary.getParams().get("encoding"));
        NormalizationRule bool = ruleOfType(source, "boolean");
        assertEquals("map_boolean", bool.getOperation());
        assertEquals("NULL", bool.getParams().get("nullValue"));
    }

    @Test
    void mapsStringNullValueToNormalizeString() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(null, null,
                Map.of("varchar", Map.of("nullValue", "<NULL>")));

        NormalizationRule rule = spec.getTarget().get(0);
        assertEquals("string", rule.getMatch().getType());
        assertEquals("normalize_string", rule.getOperation());
        assertEquals("<NULL>", rule.getParams().get("nullValue"));
    }

    @Test
    void returnsNullWhenNothingConfigured() {
        assertNull(NormalizationSpecs.fromTypeRules(null, Map.of(), Map.of()));
    }

    @Test
    void keepsOneRulePerConfiguredOperation() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("decimal", Map.of("precision", 2, "format", "0.00")), null, null);

        assertEquals(2, spec.getGlobal().size());
        assertTrue(spec.getGlobal().stream().anyMatch(r -> "format_number".equals(r.getOperation())));
        assertTrue(spec.getGlobal().stream().anyMatch(r -> "format_datetime".equals(r.getOperation())));
    }

    @Test
    void carriesSessionTimeZoneIntoDatetimeRule() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("timestamp", Map.of("sessionTimeZone", "+08:00")), null, null);

        NormalizationRule rule = ruleOfType(spec.getGlobal(), "timestamp");
        assertEquals("format_datetime", rule.getOperation());
        assertEquals("+08:00", rule.getParams().get("sessionTimeZone"));
    }

    private NormalizationRule ruleOfType(List<NormalizationRule> rules, String type) {
        return rules.stream()
                .filter(rule -> type.equals(rule.getMatch().getType()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rejectsInvalidSessionTimeZone() {
        // 时区笔误必须报错：core 侧原本会静默忽略，导致两侧时区不同却没人发现
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NormalizationSpecs.fromTypeRules(java.util.Map.of(
                        "datetime", java.util.Map.of("sessionTimeZone", "Asia/Shanghaii")),
                        java.util.Map.of(), java.util.Map.of()));
    }

    @Test
    void acceptsValidSessionTimeZone() {
        var spec = NormalizationSpecs.fromTypeRules(java.util.Map.of(
                "datetime", java.util.Map.of("sessionTimeZone", "+08:00")),
                java.util.Map.of(), java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertNotNull(spec);
    }
}
