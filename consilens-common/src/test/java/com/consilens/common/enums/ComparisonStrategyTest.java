package com.consilens.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComparisonStrategyTest {

    @Test
    void shouldOnlyAdvertiseSupportedStrategies() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ComparisonStrategy.fromString("local"));

        assertTrue(error.getMessage().contains("Valid values: checksum, join"));
    }
}
