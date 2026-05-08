package com.consilens.core.compare;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.thread.ConcurrencyConfig;
import org.junit.jupiter.api.Test;

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
        assertFalse(settings.isEnableProfiling());
        assertEquals(ChecksumAlgorithm.CONCAT, settings.getChecksumAlgorithm());
        assertEquals(LocalCompareMode.FULL, settings.getLocalCompareMode());
        assertTrue(settings.isValidateUniqueKeys());
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
                        .enableProfiling(true)
                        .checksumAlgorithm("xor")
                        .localCompareMode("row-hash")
                        .validateUniqueKeys(false)
                        .maxDifferences(25L)
                        .attributes(Map.of("concurrencyConfig", concurrencyConfig))
                        .build())
                .build();

        CompareExecutionSettings settings = CompareExecutionSettings.fromRequest(request);

        assertEquals(8, settings.getBisectionFactor());
        assertEquals(512L, settings.getBisectionThreshold());
        assertTrue(settings.isEnableProfiling());
        assertEquals(ChecksumAlgorithm.XOR, settings.getChecksumAlgorithm());
        assertEquals(LocalCompareMode.ROW_HASH, settings.getLocalCompareMode());
        assertFalse(settings.isValidateUniqueKeys());
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
}
