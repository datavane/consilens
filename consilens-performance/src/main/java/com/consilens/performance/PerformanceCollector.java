package com.consilens.performance;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance metrics collector for Consilens operations.
 * Integrates with MemoryMonitor, AdaptiveThreadPoolExecutor, and
 * ResourceMonitor
 * to collect comprehensive performance data.
 */
@Slf4j
public class PerformanceCollector {

    private final ResourceMonitor resourceMonitor;
    private final MemoryMonitor memoryMonitor;
    private AdaptiveThreadPoolExecutor threadPoolExecutor;

    private Instant startTime;
    private Instant endTime;

    // Operation tracking
    private final List<Long> operationLatencies = new ArrayList<>();
    private final Map<String, OperationStats> operationStatsMap = new ConcurrentHashMap<>();

    // Data processing tracking
    private final AtomicLong totalRowsProcessed = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicLong differencesFound = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    // Database metrics tracking
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong successfulQueries = new AtomicLong(0);
    private final AtomicLong failedQueries = new AtomicLong(0);
    private final List<Long> queryLatencies = new ArrayList<>();

    // Test metadata
    private String testName;
    private final Map<String, Object> testParameters = new ConcurrentHashMap<>();

    public PerformanceCollector() {
        this.resourceMonitor = new ResourceMonitor();
        this.memoryMonitor = new MemoryMonitor();
    }

    /**
     * Set the thread pool executor to monitor.
     */
    public void setThreadPoolExecutor(AdaptiveThreadPoolExecutor executor) {
        this.threadPoolExecutor = executor;
    }

    /**
     * Set test name.
     */
    public void setTestName(String testName) {
        this.testName = testName;
    }

    /**
     * Add test parameter.
     */
    public void addTestParameter(String key, Object value) {
        this.testParameters.put(key, value);
    }

    /**
     * Start collecting performance metrics.
     */
    public void startCollection() {
        log.info("Starting performance metrics collection");

        this.startTime = Instant.now();

        // Reset all metrics
        operationLatencies.clear();
        operationStatsMap.clear();
        totalRowsProcessed.set(0);
        totalBytesProcessed.set(0);
        differencesFound.set(0);
        errorCount.set(0);
        totalQueries.set(0);
        successfulQueries.set(0);
        failedQueries.set(0);
        queryLatencies.clear();

        // Start resource monitoring (sample every 100ms)
        resourceMonitor.startMonitoring(100);

        log.info("Performance metrics collection started");
    }

    /**
     * Stop collecting performance metrics.
     */
    public void stopCollection() {
        this.endTime = Instant.now();
        resourceMonitor.stopMonitoring();

        log.info("Performance metrics collection stopped");
    }

    /**
     * Record an operation execution.
     */
    public void recordOperation(String operationName, long durationMs) {
        synchronized (operationLatencies) {
            operationLatencies.add(durationMs);
        }

        operationStatsMap.computeIfAbsent(operationName, k -> new OperationStats())
                .recordExecution(durationMs);
    }

    /**
     * Record data processed.
     */
    public void recordDataProcessed(long rowCount, long bytes) {
        totalRowsProcessed.addAndGet(rowCount);
        totalBytesProcessed.addAndGet(bytes);
    }

    /**
     * Record differences found.
     */
    public void recordDifferences(long count) {
        differencesFound.addAndGet(count);
    }

    /**
     * Record an error.
     */
    public void recordError() {
        errorCount.incrementAndGet();
    }

    /**
     * Record a database query.
     */
    public void recordQuery(long durationMs, boolean success) {
        totalQueries.incrementAndGet();

        if (success) {
            successfulQueries.incrementAndGet();
        } else {
            failedQueries.incrementAndGet();
        }

        synchronized (queryLatencies) {
            queryLatencies.add(durationMs);
        }
    }

    /**
     * Collect all metrics and build PerformanceMetrics object.
     */
    public PerformanceMetrics collectMetrics() {
        if (endTime == null) {
            endTime = Instant.now();
        }

        long totalDurationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

        // Build metrics
        PerformanceMetrics.PerformanceMetricsBuilder builder = PerformanceMetrics.builder()
                .testName(testName)
                .startTime(startTime)
                .endTime(endTime)
                .totalDurationMs(totalDurationMs)
                .totalRowsProcessed(totalRowsProcessed.get())
                .totalBytesProcessed(totalBytesProcessed.get())
                .differencesFound(differencesFound.get())
                .errorCount(errorCount.get());

        // Calculate throughput
        if (totalDurationMs > 0) {
            double durationSec = totalDurationMs / 1000.0;
            builder.throughputRowsPerSecond(totalRowsProcessed.get() / durationSec);
            builder.throughputMBPerSecond((totalBytesProcessed.get() / (1024.0 * 1024.0)) / durationSec);
        }

        // Add test parameters
        builder.testParameters(new ConcurrentHashMap<>(testParameters));

        // Collect latency distribution
        PerformanceMetrics.LatencyDistribution latency = new PerformanceMetrics.LatencyDistribution();
        synchronized (operationLatencies) {
            for (Long lat : operationLatencies) {
                latency.addLatency(lat);
            }
        }
        latency.calculatePercentiles();
        builder.latency(latency);

        // Collect resource metrics
        PerformanceMetrics.ResourceMetrics resourceMetrics = resourceMonitor.getResourceMetrics();
        builder.resources(resourceMetrics);

        // Collect database metrics
        PerformanceMetrics.DatabaseMetrics dbMetrics = collectDatabaseMetrics();
        builder.database(dbMetrics);

        // Collect concurrency metrics
        PerformanceMetrics.ConcurrencyMetrics concurrencyMetrics = collectConcurrencyMetrics();
        builder.concurrency(concurrencyMetrics);

        return builder.build();
    }

    /**
     * Collect database metrics.
     */
    private PerformanceMetrics.DatabaseMetrics collectDatabaseMetrics() {
        PerformanceMetrics.DatabaseMetrics metrics = new PerformanceMetrics.DatabaseMetrics();

        metrics.setTotalQueries(totalQueries.get());
        metrics.setSuccessfulQueries(successfulQueries.get());
        metrics.setFailedQueries(failedQueries.get());

        // Calculate query time statistics
        synchronized (queryLatencies) {
            if (!queryLatencies.isEmpty()) {
                long sum = 0;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;

                for (Long latency : queryLatencies) {
                    sum += latency;
                    min = Math.min(min, latency);
                    max = Math.max(max, latency);
                }

                metrics.setAvgQueryTimeMs((double) sum / queryLatencies.size());
                metrics.setMinQueryTimeMs(min);
                metrics.setMaxQueryTimeMs(max);
            }
        }

        metrics.setTotalRowsFetched(totalRowsProcessed.get());
        metrics.setTotalBytesTransferred(totalBytesProcessed.get());

        return metrics;
    }

    /**
     * Collect concurrency metrics from thread pool executor.
     */
    private PerformanceMetrics.ConcurrencyMetrics collectConcurrencyMetrics() {
        PerformanceMetrics.ConcurrencyMetrics metrics = new PerformanceMetrics.ConcurrencyMetrics();

        if (threadPoolExecutor != null) {
            AdaptiveThreadPoolExecutor.ThreadPoolStats stats = threadPoolExecutor.getStats();

            metrics.setCorePoolSize(stats.getCorePoolSize());
            metrics.setMaxPoolSize(stats.getMaximumPoolSize());
            metrics.setAvgActiveThreads(stats.getActiveTasks());
            metrics.setPeakActiveThreads(stats.getActiveTasks());

            metrics.setTotalTasksSubmitted(stats.getSubmittedTasks());
            metrics.setTotalTasksCompleted(stats.getCompletedTasks());
            metrics.setTotalTasksRejected(stats.getRejectedTasks());

            metrics.setAvgQueueSize(stats.getQueueSize());
            metrics.setPeakQueueSize(stats.getQueueSize());

            metrics.setAvgTaskExecutionTimeMs(stats.getAverageExecutionTime());
        }

        return metrics;
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        operationLatencies.clear();
        operationStatsMap.clear();
        totalRowsProcessed.set(0);
        totalBytesProcessed.set(0);
        differencesFound.set(0);
        errorCount.set(0);
        totalQueries.set(0);
        successfulQueries.set(0);
        failedQueries.set(0);
        queryLatencies.clear();
        testParameters.clear();
    }

    /**
     * Shutdown the collector.
     */
    public void shutdown() {
        resourceMonitor.shutdown();
        memoryMonitor.shutdown();
    }

    /**
     * Operation statistics holder.
     */
    private static class OperationStats {
        private final AtomicLong executionCount = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private volatile long minDuration = Long.MAX_VALUE;
        private volatile long maxDuration = Long.MIN_VALUE;

        void recordExecution(long durationMs) {
            executionCount.incrementAndGet();
            totalDuration.addAndGet(durationMs);

            synchronized (this) {
                minDuration = Math.min(minDuration, durationMs);
                maxDuration = Math.max(maxDuration, durationMs);
            }
        }

        double getAverageDuration() {
            long count = executionCount.get();
            return count > 0 ? (double) totalDuration.get() / count : 0.0;
        }
    }
}
