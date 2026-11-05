package com.consilens.core.compare;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.thread.ConcurrencyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareExecutionSettingsTest {

    @Test
    void shouldUseProductionDefaultsWhenRequestHasNoExecutionOptions() {
        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(null);

        assertEquals(32, settings.getBisectionFactor());
        assertEquals(16_384L, settings.getBisectionThreshold());
        assertEquals(ChecksumAlgorithm.CONCAT, settings.getChecksumAlgorithm());
        assertEquals(LocalCompareMode.FULL, settings.getLocalCompareMode());
        // 默认开放档：重复键走多重集配对；严格档（命中重复键即失败）需显式开启
        assertFalse(settings.isValidateUniqueKeys());
        // 默认可空键严格档：键列 nullable 时直接拒绝，需显式开启
        assertFalse(settings.isAllowNullableKeys());
        assertEquals(1_000_000L, settings.getMaxDifferences());
        assertNotNull(settings.getConcurrencyConfig());
    }

    @Test
    void shouldResolveExecutionOptionsAndCustomConcurrencyConfig() {
        ConcurrencyConfig concurrencyConfig = new ConcurrencyConfig(
                new ConcurrencyConfig.PoolConfig(2, 4, 16, 30L, "test-io-"),
                new ConcurrencyConfig.PoolConfig(1, 2, 16, 30L, "test-cpu-"));
        CompareRequest request = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .bisectionFactor(8)
                        .bisectionThreshold(512L)
                        .checksumAlgorithm("xor")
                        .localCompareMode("row-hash")
                        .validateUniqueKeys(false)
                        .allowNullableKeys(true)
                        .maxDifferences(25L)
                        .attributes(Map.of("concurrencyConfig", concurrencyConfig))
                        .build())
                .build();

        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(request);

        assertEquals(8, settings.getBisectionFactor());
        assertEquals(512L, settings.getBisectionThreshold());
        assertEquals(ChecksumAlgorithm.XOR, settings.getChecksumAlgorithm());
        assertEquals(LocalCompareMode.ROW_HASH, settings.getLocalCompareMode());
        assertFalse(settings.isValidateUniqueKeys());
        assertTrue(settings.isAllowNullableKeys());
        assertTrue(settings.toDifferConfig().isAllowNullableKeys());
        assertEquals(25L, settings.getMaxDifferences());
        assertSame(concurrencyConfig, settings.getConcurrencyConfig());
    }

    @Test
    void shouldResolveMaxDifferencesFromAttributesAndClampInvalidLimits() {
        CompareRequest stringAttributeRequest = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .attributes(Map.of("maxDifferences", " 42 "))
                        .build())
                .build();
        CompareRequest numberAttributeRequest = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .attributes(Map.of("maxDifferences", 7))
                        .build())
                .build();
        CompareRequest explicitInvalidRequest = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .maxDifferences(0L)
                        .attributes(Map.of("maxDifferences", 42L))
                        .build())
                .build();

        assertEquals(42L, CompareExecutionSettings.fromRequest(stringAttributeRequest).getMaxDifferences());
        assertEquals(7L, CompareExecutionSettings.fromRequest(numberAttributeRequest).getMaxDifferences());
        assertEquals(1L, CompareExecutionSettings.fromRequest(explicitInvalidRequest).getMaxDifferences());
    }
    @Test
    void shouldResolveDecimalScaleFromNormalizationSpec() {
        com.consilens.connector.api.normalization.NormalizationSpec spec =
                com.consilens.connector.api.normalization.NormalizationSpec.builder()
                        .global(List.of(com.consilens.connector.api.normalization.NormalizationRule.builder()
                                .match(com.consilens.connector.api.normalization.MatchSpec.builder()
                                        .type("decimal").build())
                                .operation("format_number")
                                .params(Map.of("precision", 6))
                                .build()))
                        .build();
        assertEquals(6, CompareExecutionSettings.resolveDecimalScale(spec));
        assertEquals(com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE,
                CompareExecutionSettings.resolveDecimalScale(null));
    }

    @Test
    void shouldResolveSessionZoneFromNormalizationParams() {
        com.consilens.connector.api.normalization.NormalizationSpec spec =
                com.consilens.connector.api.normalization.NormalizationSpec.builder()
                        .global(List.of(com.consilens.connector.api.normalization.NormalizationRule.builder()
                                .match(com.consilens.connector.api.normalization.MatchSpec.builder()
                                        .type("timestamp").build())
                                .operation("format_datetime")
                                .params(Map.of("sessionTimeZone", "+08:00"))
                                .build()))
                        .build();

        assertEquals(java.time.ZoneId.of("+08:00"), CompareExecutionSettings.resolveSessionZone(spec));
        // 笔误/非法时区视为未配置，不能因此让整次比对失败
        assertEquals(null, CompareExecutionSettings.resolveSessionZone(
                com.consilens.connector.api.normalization.NormalizationSpec.builder()
                        .global(List.of(com.consilens.connector.api.normalization.NormalizationRule.builder()
                                .match(com.consilens.connector.api.normalization.MatchSpec.builder()
                                        .type("timestamp").build())
                                .operation("format_datetime")
                                .params(Map.of("sessionTimeZone", "Mars/Olympus"))
                                .build()))
                        .build()));
        assertEquals(null, CompareExecutionSettings.resolveSessionZone(null));
    }

    @Test
    void shouldResolveBudgetFieldsFromOptionsAndClampInvalidLimits() {
        CompareRequest request = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .maxDurationMs(60_000L)
                        .maxRowsScanned(500_000L)
                        .maxBytesFetched(1024L)
                        .build())
                .build();

        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(request);

        assertEquals(60_000L, settings.getMaxDurationMs());
        assertEquals(500_000L, settings.getMaxRowsScanned());
        assertEquals(1024L, settings.getMaxBytesFetched());
        assertEquals(60_000L, settings.toDifferConfig().getMaxDurationMs());
        assertEquals(500_000L, settings.toDifferConfig().getMaxRowsScanned());
        assertEquals(1024L, settings.toDifferConfig().getMaxBytesFetched());
    }

    @Test
    void shouldDefaultBudgetsToUnlimited() {
        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(null);

        assertEquals(Long.MAX_VALUE, settings.getMaxDurationMs());
        assertEquals(Long.MAX_VALUE, settings.getMaxRowsScanned());
        assertEquals(Long.MAX_VALUE, settings.getMaxBytesFetched());
        assertEquals(Long.MAX_VALUE, settings.toDifferConfig().getMaxRowsScanned());
    }

    @Test
    void shouldResolveBudgetsFromAttributesAndIgnoreInvalidValues() {
        CompareRequest request = CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder()
                        .attributes(java.util.Map.of(
                                "maxDurationMs", "30000",
                                "maxRowsScanned", -5L,
                                "maxBytesFetched", 2048))
                        .build())
                .build();

        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(request);

        assertEquals(30_000L, settings.getMaxDurationMs());
        assertEquals(Long.MAX_VALUE, settings.getMaxRowsScanned());
        assertEquals(2048L, settings.getMaxBytesFetched());
    }

    @Test
    void shouldUseNormalizationPrecisionFromRequest() {
        com.consilens.connector.api.normalization.NormalizationSpec spec =
                com.consilens.connector.api.normalization.NormalizationSpec.builder()
                        .target(List.of(com.consilens.connector.api.normalization.NormalizationRule.builder()
                                .match(com.consilens.connector.api.normalization.MatchSpec.builder()
                                        .type("numeric").build())
                                .operation("format_number")
                                .params(Map.of("precision", 8))
                                .build()))
                        .build();
        CompareRequest request = CompareRequest.builder()
                .normalizationSpec(spec)
                .build();

        assertEquals(8, CompareExecutionSettings.fromRequest(request).getDecimalScale());
    }

    @Test
    void shouldPreserveNormalizationSettingsWhenDowngradingChecksumAlgorithm() {
        CompareExecutionSettings original = CompareExecutionSettings.builder()
                .checksumAlgorithm(ChecksumAlgorithm.XOR)
                .maxDifferences(77L)
                .decimalScale(6)
                .allowNullableKeys(true)
                .sessionZone(java.time.ZoneId.of("+08:00"))
                .build();

        CompareExecutionSettings downgraded = original.withChecksumAlgorithm(ChecksumAlgorithm.CONCAT);

        assertEquals(ChecksumAlgorithm.CONCAT, downgraded.getChecksumAlgorithm());
        assertEquals(77L, downgraded.getMaxDifferences());
        assertEquals(6, downgraded.getDecimalScale());
        assertTrue(downgraded.isAllowNullableKeys());
        assertEquals(java.time.ZoneId.of("+08:00"), downgraded.getSessionZone());
    }
}
