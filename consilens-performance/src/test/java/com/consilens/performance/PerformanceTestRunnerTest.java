package com.consilens.performance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceTestRunnerTest {

    @Test
    void shouldRunSmokeTestAndCollectMetrics() {
        PerformanceTestRunner runner = new PerformanceTestRunner();
        try {
            AtomicInteger calls = new AtomicInteger();
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .testName("runner-smoke")
                    .warmupIterations(1)
                    .testIterations(5)
                    .concurrencyLevel(2)
                    .monitoringIntervalMs(10)
                    .build();

            PerformanceTestResult result = runner.runTest(config, () -> {
                calls.incrementAndGet();
                return PerformanceTestRunner.TestResult.success(10, 2048, 1);
            });

            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertNotNull(result.getMetrics());
            assertEquals(6, calls.get());
            assertEquals(5, result.getTestResults().size());
            assertEquals(50, result.getMetrics().getTotalRowsProcessed());
            assertEquals(10_240, result.getMetrics().getTotalBytesProcessed());
            assertEquals(5, result.getMetrics().getDifferencesFound());
            assertEquals(0, result.getMetrics().getErrorCount());
            assertEquals(5, result.getMetrics().getConcurrency().getTotalTasksSubmitted());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void shouldReturnFailureWhenIterationsFail() {
        PerformanceTestRunner runner = new PerformanceTestRunner();
        try {
            PerformanceTestConfig config = PerformanceTestConfig.builder()
                    .testName("runner-failure")
                    .warmupIterations(0)
                    .testIterations(3)
                    .concurrencyLevel(1)
                    .monitoringIntervalMs(10)
                    .build();

            PerformanceTestResult result = runner.runTest(config, () -> {
                throw new IllegalStateException("boom");
            });

            assertFalse(result.isSuccess());
            assertNotNull(result.getMetrics());
            assertEquals(3, result.getMetrics().getErrorCount());
            assertTrue(result.getErrorMessage().contains("3"));
        } finally {
            runner.shutdown();
        }
    }
}
