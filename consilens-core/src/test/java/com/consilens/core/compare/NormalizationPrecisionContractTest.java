package com.consilens.core.compare;

import com.consilens.connector.api.normalization.MatchSpec;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NormalizationPrecisionContractTest {

    @Test
    void flagsPrecisionBelowLocalComparisonPrecision() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("decimal", "format_number", Map.of("precision", 2))))
                .build();

        assertEquals(2, DefaultCompareRuntime.coarseDecimalPrecision(spec));
    }

    @Test
    void ignoresPrecisionAtOrAboveLocalComparisonPrecision() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("decimal", "format_number", Map.of("precision", 6))))
                .source(List.of(rule("decimal", "format_number", Map.of("precision", 4))))
                .build();

        assertNull(DefaultCompareRuntime.coarseDecimalPrecision(spec));
    }

    @Test
    void picksSmallestOffendingPrecisionAcrossSides() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("decimal", "format_number", Map.of("precision", 3))))
                .target(List.of(rule("decimal", "format_number", Map.of("precision", 1))))
                .build();

        assertEquals(1, DefaultCompareRuntime.coarseDecimalPrecision(spec));
    }

    @Test
    void ignoresRulesWithoutPrecision() {
        NormalizationSpec spec = NormalizationSpec.builder()
                .global(List.of(rule("timestamp", "format_datetime", Map.of("timezone", "UTC"))))
                .build();

        assertNull(DefaultCompareRuntime.coarseDecimalPrecision(spec));
        assertNull(DefaultCompareRuntime.coarseDecimalPrecision(null));
    }

    private NormalizationRule rule(String type, String operation, Map<String, Object> params) {
        return NormalizationRule.builder()
                .match(MatchSpec.builder().type(type).build())
                .operation(operation)
                .params(params)
                .build();
    }
}
