package com.consilens.core.thread;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConcurrencyConfigTest {

    @Test
    void shouldRejectInvalidPoolSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcurrencyConfig.PoolConfig(0, 4, 10, 60L, "bad-"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcurrencyConfig.PoolConfig(4, 2, 10, 60L, "bad-"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcurrencyConfig.PoolConfig(2, 4, -1, 60L, "bad-"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcurrencyConfig.PoolConfig(2, 4, 10, -1L, "bad-"));
    }

    @Test
    void shouldAllowValidExecutorConfiguration() {
        assertDoesNotThrow(() -> {
            ExecutorProvider provider = new ExecutorProvider(new ConcurrencyConfig(
                    new ConcurrencyConfig.PoolConfig(2, 4, 16, 30L, "test-io-"),
                    new ConcurrencyConfig.PoolConfig(1, 2, 16, 30L, "test-cpu-")
            ));
            provider.shutdownNow();
        });
    }
}
