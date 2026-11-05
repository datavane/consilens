package com.consilens.cli.model.normalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TolerancePolicyConfigTest {

    @Test
    void shouldDefaultToIgnoreWithDefaultSampleLimit() {
        TolerancePolicyConfig config = new TolerancePolicyConfig();

        assertEquals(TolerancePolicyConfig.IGNORE, config.normalizedMode());
        assertEquals(TolerancePolicyConfig.DEFAULT_REPORT_SAMPLES, config.effectiveReportSamples());
    }

    @Test
    void shouldRejectUnknownMode() {
        TolerancePolicyConfig config = TolerancePolicyConfig.builder()
                .withinTolerance("ignore-diff")
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(failure.getMessage().contains("withinTolerance"));
    }

    @Test
    void shouldRejectNegativeSampleLimit() {
        TolerancePolicyConfig config = TolerancePolicyConfig.builder()
                .reportSamples(-1)
                .build();

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void shouldRejectUnreasonablyLargeSampleLimit() {
        TolerancePolicyConfig config = TolerancePolicyConfig.builder()
                .reportSamples(TolerancePolicyConfig.MAX_REPORT_SAMPLES + 1)
                .build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(failure.getMessage().contains("reportSamples"),
                "样本上限必须硬失败，不能静默把大数字灌进内存");
    }
}
