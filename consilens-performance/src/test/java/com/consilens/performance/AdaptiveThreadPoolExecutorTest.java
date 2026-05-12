package com.consilens.performance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveThreadPoolExecutorTest {

    @Test
    void shouldValidatePoolConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveThreadPoolExecutor(2, 1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveThreadPoolExecutor(1, 1, -1, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveThreadPoolExecutor(1, 1, 1, null));
    }

    @Test
    void shouldSubmitTasksCollectStatsAndRejectAfterShutdown() throws Exception {
        AdaptiveThreadPoolExecutor executor = new AdaptiveThreadPoolExecutor(1, 2, 1, TimeUnit.SECONDS);
        try {
            CompletableFuture<String> future = executor.submit(() -> "ok");

            assertEquals("ok", future.get(5, TimeUnit.SECONDS));
            AdaptiveThreadPoolExecutor.ThreadPoolStats stats = executor.getStats();
            assertEquals(1, stats.getSubmittedTasks());
            assertEquals(1, stats.getCompletedTasks());
        } finally {
            executor.shutdown();
        }

        assertTrue(executor.isShutdown());
        CompletableFuture<String> rejected = executor.submit(() -> "late");
        assertThrows(ExecutionException.class, rejected::get);
        assertEquals(1, executor.getStats().getRejectedTasks());
    }
}
