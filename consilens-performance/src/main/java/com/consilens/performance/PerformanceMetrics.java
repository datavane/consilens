package com.consilens.performance;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive performance metrics for Consilens operations.
 * Tracks timing, resource usage, database operations, and concurrency metrics.
 */
@Data
@Builder
public class PerformanceMetrics {

    // ==================== Time Metrics ====================

    /** Test start timestamp */
    private Instant startTime;

    /** Test end timestamp */
    private Instant endTime;

    /** Total duration in milliseconds */
    private long totalDurationMs;

    /** Throughput in rows per second */
    private double throughputRowsPerSecond;

    /** Throughput in MB per second */
    private double throughputMBPerSecond;

    /** Latency distribution */
    @Builder.Default
    private LatencyDistribution latency = new LatencyDistribution();

    // ==================== Resource Metrics ====================

    /** Resource usage metrics */
    @Builder.Default
    private ResourceMetrics resources = new ResourceMetrics();

    // ==================== Database Metrics ====================

    /** Database operation metrics */
    @Builder.Default
    private DatabaseMetrics database = new DatabaseMetrics();

    // ==================== Concurrency Metrics ====================

    /** Concurrency metrics */
    @Builder.Default
    private ConcurrencyMetrics concurrency = new ConcurrencyMetrics();

    // ==================== Data Processing Metrics ====================

    /** Total rows processed */
    private long totalRowsProcessed;

    /** Total bytes processed */
    private long totalBytesProcessed;

    /** Number of differences found */
    private long differencesFound;

    /** Error count */
    private long errorCount;

    // ==================== Test Metadata ====================

    /** Test name */
    private String testName;

    /** Test configuration parameters */
    @Builder.Default
    private Map<String, Object> testParameters = new ConcurrentHashMap<>();

    /**
     * Latency distribution metrics.
     */
    @Data
    public static class LatencyDistribution {
        /** Minimum latency in milliseconds */
        private long minMs;

        /** Maximum latency in milliseconds */
        private long maxMs;

        /** Average latency in milliseconds */
        private double avgMs;

        /** Median latency (P50) in milliseconds */
        private long p50Ms;

        /** 95th percentile latency in milliseconds */
        private long p95Ms;

        /** 99th percentile latency in milliseconds */
        private long p99Ms;

        /** 99.9th percentile latency in milliseconds */
        private long p999Ms;

        /** Standard deviation */
        private double stdDevMs;

        /** All recorded latencies for percentile calculation */
        private transient List<Long> allLatencies = new ArrayList<>();

        /**
         * Add a latency measurement.
         */
        public void addLatency(long latencyMs) {
            if (allLatencies == null) {
                allLatencies = new ArrayList<>();
            }
            allLatencies.add(latencyMs);
        }

        /**
         * Calculate percentiles from recorded latencies.
         */
        public void calculatePercentiles() {
            if (allLatencies == null || allLatencies.isEmpty()) {
                return;
            }

            List<Long> sorted = new ArrayList<>(allLatencies);
            sorted.sort(Long::compareTo);

            int size = sorted.size();
            minMs = sorted.get(0);
            maxMs = sorted.get(size - 1);

            // Calculate average
            double sum = 0;
            for (long latency : sorted) {
                sum += latency;
            }
            avgMs = sum / size;

            // Calculate percentiles
            p50Ms = sorted.get((int) (size * 0.50));
            p95Ms = sorted.get((int) (size * 0.95));
            p99Ms = sorted.get((int) (size * 0.99));
            p999Ms = sorted.get((int) (size * 0.999));

            // Calculate standard deviation
            double variance = 0;
            for (long latency : sorted) {
                variance += Math.pow(latency - avgMs, 2);
            }
            stdDevMs = Math.sqrt(variance / size);
        }
    }

    /**
     * Resource usage metrics.
     */
    @Data
    public static class ResourceMetrics {
        // Memory metrics
        private long initialHeapMB;
        private long peakHeapMB;
        private long finalHeapMB;
        private long avgHeapMB;

        private long initialNonHeapMB;
        private long peakNonHeapMB;
        private long finalNonHeapMB;

        // GC metrics
        private long gcCount;
        private long gcTimeMs;
        private double avgGcTimeMs;

        // CPU metrics
        private double avgCpuUsagePercent;
        private double peakCpuUsagePercent;
        private double avgSystemLoadAverage;

        // Disk I/O metrics
        private long diskReadBytes;
        private long diskWriteBytes;
        private long diskReadOps;
        private long diskWriteOps;

        // Thread metrics
        private int peakThreadCount;
        private int avgThreadCount;
    }

    /**
     * Database operation metrics.
     */
    @Data
    public static class DatabaseMetrics {
        // Connection pool metrics
        private int maxPoolSize;
        private int avgActiveConnections;
        private int peakActiveConnections;
        private int avgIdleConnections;
        private long totalConnectionsCreated;
        private long totalConnectionsClosed;
        private long connectionLeaks;

        // Query metrics
        private long totalQueries;
        private long successfulQueries;
        private long failedQueries;
        private double avgQueryTimeMs;
        private long maxQueryTimeMs;
        private long minQueryTimeMs;

        // Data transfer metrics
        private long totalRowsFetched;
        private long totalBytesTransferred;
    }

    /**
     * Concurrency metrics.
     */
    @Data
    public static class ConcurrencyMetrics {
        // Thread pool metrics
        private int corePoolSize;
        private int maxPoolSize;
        private int avgActiveThreads;
        private int peakActiveThreads;

        // Task metrics
        private long totalTasksSubmitted;
        private long totalTasksCompleted;
        private long totalTasksRejected;
        private long totalTasksFailed;

        // Queue metrics
        private int avgQueueSize;
        private int peakQueueSize;

        // Timing metrics
        private double avgTaskExecutionTimeMs;
        private long maxTaskExecutionTimeMs;
        private double avgTaskWaitTimeMs;
        private long maxTaskWaitTimeMs;
    }

    /**
     * Get a summary string of the metrics.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Metrics Summary:\n");
        sb.append(String.format("  Test: %s\n", testName));
        sb.append(String.format("  Duration: %,d ms\n", totalDurationMs));
        sb.append(String.format("  Throughput: %.2f rows/sec, %.2f MB/sec\n",
                throughputRowsPerSecond, throughputMBPerSecond));
        sb.append(String.format("  Rows Processed: %,d\n", totalRowsProcessed));
        sb.append(String.format("  Differences Found: %,d\n", differencesFound));
        sb.append(String.format("  Latency - Avg: %.2f ms, P95: %d ms, P99: %d ms\n",
                latency.avgMs, latency.p95Ms, latency.p99Ms));
        sb.append(String.format("  Memory - Peak: %d MB, GC Time: %d ms\n",
                resources.peakHeapMB, resources.gcTimeMs));
        sb.append(String.format("  CPU - Avg: %.2f%%, Peak: %.2f%%\n",
                resources.avgCpuUsagePercent, resources.peakCpuUsagePercent));
        sb.append(String.format("  Errors: %d\n", errorCount));
        return sb.toString();
    }
}
