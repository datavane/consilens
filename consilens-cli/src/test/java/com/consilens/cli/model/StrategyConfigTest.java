package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrategyConfigTest {

    @Test
    void shouldAcceptSupportedModesAlgorithmsAndLocalCompareModes() {
        assertDoesNotThrow(() -> StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(1L)
                .localCompare(LocalCompareConfig.builder().mode("full").build())
                .build()
                .validate());

        assertDoesNotThrow(() -> StrategyConfig.builder()
                .mode("join")
                .algorithm("xor")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(1L)
                .localCompare(LocalCompareConfig.builder().mode("row-hash").build())
                .build()
                .validate());
    }

    @Test
    void shouldRejectUnsupportedModeAlgorithmLocalCompareAndLimits() {
        assertThrows(ValidationException.class, () -> StrategyConfig.builder()
                .mode("local")
                .algorithm("concat")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(1L)
                .build()
                .validate());

        assertThrows(ValidationException.class, () -> StrategyConfig.builder()
                .mode("checksum")
                .algorithm("md5")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(1L)
                .build()
                .validate());

        assertThrows(ValidationException.class, () -> StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(1L)
                .localCompare(LocalCompareConfig.builder().mode("sample").build())
                .build()
                .validate());

        assertThrows(ValidationException.class, () -> StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(2)
                .batchSize(1)
                .maxDifferences(0L)
                .build()
                .validate());
    }
}
