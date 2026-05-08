package com.consilens.performance;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Performance test runner for executing performance tests and collecting
 * metrics.
 */
@Slf4j
public class PerformanceTestRunner {

    private final PerformanceCollector collector;
    private AdaptiveThreadPoolExecutor threadPool;

    public PerformanceTestRunner() {
        this.collector = new PerformanceCollector();
    }

    /**
     * Run a performance test with the given configuration and test logic.
     */
    public PerformanceTestResult runTest(PerformanceTestConfig config, Callable<TestResult> testLogic) {
        config.validate();

        log.info("Starting performance test: {}", config.getTestName());
        log.info("Configuration: warmup={}, iterations={}, concurrency={}",
                config.getWarmupIterations(), config.getTestIterations(), config.getConcurrencyLevel());

        try {
            // Initialize thread pool
            initializeThreadPool(config);

            // Set up collector
            collector.setTestName(config.getTestName());
            collector.setThreadPoolExecutor(threadPool);
            collector.setMonitoringIntervalMs(config.getMonitoringIntervalMs());
            config.getTestParameters().forEach(collector::addTestParameter);

            // Warmup phase
            if (config.getWarmupIterations() > 0) {
                log.info("Running warmup phase: {} iterations", config.getWarmupIterations());
                warmup(config, testLogic);
            }

            // Test phase
            log.info("Running test phase: {} iterations", config.getTestIterations());
            List<TestResult> results;
            boolean collecting = false;
            try {
                collector.startCollection();
                collecting = true;
                results = executeTest(config, testLogic);
            } finally {
                if (collecting) {
                    collector.stopCollection();
                }
            }

            // Collect metrics
            PerformanceMetrics metrics = collector.collectMetrics();

            // Build result
            PerformanceTestResult result = PerformanceTestResult.builder()
                    .config(config)
                    .metrics(metrics)
                    .testResults(results)
                    .success(metrics.getErrorCount() == 0)
                    .errorMessage(metrics.getErrorCount() == 0 ? null : "Test completed with errors: " + metrics.getErrorCount())
                    .build();

            log.info("Performance test completed with success={}", result.isSuccess());
            log.info(metrics.getSummary());

            return result;

        } catch (Exception e) {
            log.error("Performance test failed", e);

            return PerformanceTestResult.builder()
                    .config(config)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();

        } finally {
            shutdownThreadPool();
        }
    }

    /**
     * Initialize thread pool based on configuration.
     */
    private void initializeThreadPool(PerformanceTestConfig config) {
        int concurrency = config.getConcurrencyLevel();

        // Create adaptive thread pool with appropriate parameters
        this.threadPool = new AdaptiveThreadPoolExecutor(
                concurrency,  // minPoolSize
                concurrency * 2,  // maxPoolSize
                60L, TimeUnit.SECONDS  // keep alive time
        );

        log.debug("Initialized thread pool with core size: {}", concurrency);
    }

    /**
     * Shutdown thread pool.
     */
    private void shutdownThreadPool() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    /**
     * Warmup phase to stabilize JVM.
     */
    private void warmup(PerformanceTestConfig config, Callable<TestResult> testLogic) {
        try {
            for (int i = 0; i < config.getWarmupIterations(); i++) {
                testLogic.call();
            }

            // Give GC a chance to run
            System.gc();
            Thread.sleep(100);

            log.info("Warmup completed");

        } catch (Exception e) {
            log.warn("Warmup phase encountered error: {}", e.getMessage());
        }
    }

    /**
     * Execute the actual test.
     */
    private List<TestResult> executeTest(PerformanceTestConfig config, Callable<TestResult> testLogic)
            throws InterruptedException {

        List<TestResult> results = new ArrayList<>();
        int iterations = config.getTestIterations();
        int concurrency = config.getConcurrencyLevel();
        PerformanceTestConfig.LoadPattern loadPattern = config.getLoadPattern();

        // Execute based on load pattern
        switch (loadPattern) {
            case RAMP_UP:
                results = executeRampUp(iterations, concurrency, testLogic);
                break;
            case RAMP_DOWN:
                results = executeRampDown(iterations, concurrency, testLogic);
                break;
            case SPIKE:
                results = executeSpike(iterations, concurrency, testLogic);
                break;
            case STEP:
                results = executeStep(iterations, concurrency, testLogic);
                break;
            case CONSTANT:
            default:
                if (concurrency == 1) {
                    // Sequential execution
                    results = executeSequential(iterations, testLogic);
                } else {
                    // Concurrent execution
                    results = executeConcurrent(iterations, concurrency, testLogic);
                }
                break;
        }

        return results;
    }

    /**
     * Execute ramp-up load pattern: gradually increase concurrency.
     */
    private List<TestResult> executeRampUp(int totalIterations, int maxConcurrency, Callable<TestResult> testLogic)
            throws InterruptedException {
        List<TestResult> results = new ArrayList<>();

        // Increase concurrency step by step
        for (int concurrency = 1; concurrency <= maxConcurrency; concurrency++) {
            int iterationsPerStep = totalIterations / maxConcurrency;
            // Execute with current concurrency level
            List<TestResult> stepResults = executeConcurrent(iterationsPerStep, concurrency, testLogic);
            results.addAll(stepResults);

            // Short delay between steps
            Thread.sleep(500);
        }

        // Execute any remaining iterations
        int remaining = totalIterations % maxConcurrency;
        if (remaining > 0) {
            results.addAll(executeConcurrent(remaining, maxConcurrency, testLogic));
        }

        return results;
    }

    /**
     * Execute ramp-down load pattern: gradually decrease concurrency.
     */
    private List<TestResult> executeRampDown(int totalIterations, int maxConcurrency, Callable<TestResult> testLogic)
            throws InterruptedException {
        List<TestResult> results = new ArrayList<>();

        // Decrease concurrency step by step
        for (int concurrency = maxConcurrency; concurrency >= 1; concurrency--) {
            int iterationsPerStep = totalIterations / maxConcurrency;
            // Execute with current concurrency level
            List<TestResult> stepResults = executeConcurrent(iterationsPerStep, concurrency, testLogic);
            results.addAll(stepResults);

            // Short delay between steps
            Thread.sleep(500);
        }

        // Execute any remaining iterations
        int remaining = totalIterations % maxConcurrency;
        if (remaining > 0) {
            results.addAll(executeConcurrent(remaining, 1, testLogic));
        }

        return results;
    }

    /**
     * Execute spike load pattern: sudden increase and decrease in concurrency.
     */
    private List<TestResult> executeSpike(int totalIterations, int maxConcurrency, Callable<TestResult> testLogic)
            throws InterruptedException {
        List<TestResult> results = new ArrayList<>();

        // Low load phase
        int lowPhaseIterations = totalIterations / 4;
        results.addAll(executeConcurrent(lowPhaseIterations, 1, testLogic));

        // Spike phase - max concurrency
        int spikePhaseIterations = totalIterations / 2;
        results.addAll(executeConcurrent(spikePhaseIterations, maxConcurrency, testLogic));

        // Recovery phase - low load again
        int recoveryPhaseIterations = totalIterations - lowPhaseIterations - spikePhaseIterations;
        results.addAll(executeConcurrent(recoveryPhaseIterations, 1, testLogic));

        return results;
    }

    /**
     * Execute step load pattern: discrete concurrency levels.
     */
    private List<TestResult> executeStep(int totalIterations, int maxConcurrency, Callable<TestResult> testLogic)
            throws InterruptedException {
        List<TestResult> results = new ArrayList<>();

        // Step interval: 25%, 50%, 75%, 100% of max concurrency
        int[] concurrencySteps = {
                Math.max(1, maxConcurrency / 4),
                Math.max(1, maxConcurrency / 2),
                Math.max(1, maxConcurrency * 3 / 4),
                maxConcurrency
        };
        int iterationsPerStep = totalIterations / concurrencySteps.length;

        for (int concurrency : concurrencySteps) {
            // Execute with current concurrency level
            List<TestResult> stepResults = executeConcurrent(iterationsPerStep, concurrency, testLogic);
            results.addAll(stepResults);

            // Delay between steps
            Thread.sleep(1000);
        }

        // Execute any remaining iterations
        int remaining = totalIterations % concurrencySteps.length;
        if (remaining > 0) {
            results.addAll(executeConcurrent(remaining, maxConcurrency, testLogic));
        }

        return results;
    }

    /**
     * Execute test sequentially.
     */
    private List<TestResult> executeSequential(int iterations, Callable<TestResult> testLogic) {
        List<TestResult> results = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            try {
                long startTime = System.currentTimeMillis();
                TestResult result = testLogic.call();
                long duration = System.currentTimeMillis() - startTime;

                // Record metrics
                collector.recordOperation("test-iteration", duration);
                if (result != null) {
                    collector.recordDataProcessed(result.getRowsProcessed(), result.getBytesProcessed());
                    collector.recordDifferences(result.getDifferencesFound());
                }

                results.add(result);

            } catch (Exception e) {
                log.warn("Test iteration {} failed: {}", i, e.getMessage());
                log.debug("Test iteration failure details", e);
                collector.recordError();
            }
        }

        return results;
    }

    /**
     * Execute test concurrently.
     */
    private List<TestResult> executeConcurrent(int iterations, int concurrency, Callable<TestResult> testLogic)
            throws InterruptedException {

        List<TestResult> results = new ArrayList<>();
        if (iterations <= 0) {
            return results;
        }
        int effectiveConcurrency = Math.max(1, concurrency);
        Semaphore permits = new Semaphore(effectiveConcurrency);
        CountDownLatch latch = new CountDownLatch(iterations);

        for (int i = 0; i < iterations; i++) {
            threadPool.submit(() -> {
                boolean permitAcquired = false;
                try {
                    permits.acquire();
                    permitAcquired = true;
                    long startTime = System.currentTimeMillis();
                    TestResult result = testLogic.call();
                    long duration = System.currentTimeMillis() - startTime;

                    // Record metrics
                    collector.recordOperation("test-iteration", duration);
                    if (result != null) {
                        collector.recordDataProcessed(result.getRowsProcessed(), result.getBytesProcessed());
                        collector.recordDifferences(result.getDifferencesFound());
                    }

                    synchronized (results) {
                        results.add(result);
                    }

                    return result;

                } catch (Exception e) {
                    log.warn("Concurrent test iteration failed: {}", e.getMessage());
                    log.debug("Concurrent test iteration failure details", e);
                    collector.recordError();
                    return null;

                } finally {
                    if (permitAcquired) {
                        permits.release();
                    }
                    latch.countDown();
                }
            });
        }

        // Wait for all tasks to complete
        boolean completed = latch.await(10, TimeUnit.MINUTES);
        if (!completed) {
            collector.recordError();
            throw new IllegalStateException("Performance test did not complete within timeout");
        }

        return results;
    }

    /**
     * Shutdown the test runner.
     */
    public void shutdown() {
        collector.shutdown();
        shutdownThreadPool();
    }

    /**
     * Test result holder.
     */
    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TestResult {
        private long rowsProcessed;
        private long bytesProcessed;
        private long differencesFound;
        private boolean success;
        private String errorMessage;

        public static TestResult success(long rowsProcessed, long bytesProcessed, long differencesFound) {
            return TestResult.builder()
                    .rowsProcessed(rowsProcessed)
                    .bytesProcessed(bytesProcessed)
                    .differencesFound(differencesFound)
                    .success(true)
                    .build();
        }

        public static TestResult failure(String errorMessage) {
            return TestResult.builder()
                    .success(false)
                    .errorMessage(errorMessage)
                    .build();
        }
    }
}
