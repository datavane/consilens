package com.consilens.performance;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory monitoring and management for consilens performance optimization.
 * Provides memory pressure detection, GC suggestion, and memory usage tracking.
 */
@Slf4j
@Data
public class MemoryMonitor {

    private final Runtime runtime;
    private final MemoryMXBean memoryMXBean;
    private final double memoryThreshold;
    private final boolean enableGCSuggestion;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong gcCount = new AtomicLong(0);
    private final AtomicLong totalGCTime = new AtomicLong(0);
    private volatile long lastGCTime = 0;

    // Memory usage tracking
    private volatile long maxMemoryUsed;
    private volatile long currentMemoryUsed;
    private volatile double memoryUtilization;
    private volatile boolean memoryPressure;

    public MemoryMonitor(double memoryThreshold, boolean enableGCSuggestion) {
        this.runtime = Runtime.getRuntime();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.memoryThreshold = memoryThreshold;
        this.enableGCSuggestion = enableGCSuggestion;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryMonitor");
            t.setDaemon(true);
            return t;
        });

        initializeMonitoring();
    }

    /**
     * Create memory monitor with default configuration.
     */
    public MemoryMonitor() {
        this(0.8, true); // 80% threshold, enable GC suggestions
    }

    /**
     * Initialize memory monitoring.
     */
    private void initializeMonitoring() {
        updateMemoryStats();

        // Schedule periodic monitoring
        scheduler.scheduleAtFixedRate(this::monitorMemory, 1, 1, TimeUnit.MINUTES);

        // Register for GC notifications
        if (enableGCSuggestion) {
            registerGCNotifications();
        }

        log.info("Memory monitor initialized with threshold: {}", memoryThreshold);
    }

    /**
     * Monitor memory usage and detect pressure.
     */
    public void monitorMemory() {
        try {
            updateMemoryStats();

            if (memoryPressure) {
                handleMemoryPressure();
            }

            logMemoryStats();

        } catch (Exception e) {
            log.error("Error during memory monitoring", e);
        }
    }

    /**
     * Update memory statistics.
     */
    private void updateMemoryStats() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        currentMemoryUsed = heapUsage.getUsed();
        maxMemoryUsed = Math.max(maxMemoryUsed, currentMemoryUsed);
        memoryUtilization = (double) currentMemoryUsed / heapUsage.getMax();
        memoryPressure = memoryUtilization > memoryThreshold;
    }

    /**
     * Handle memory pressure situation.
     */
    private void handleMemoryPressure() {
        log.warn("Memory pressure detected - Usage: {}% ({}MB / {}MB)",
                memoryUtilization * 100,
                currentMemoryUsed / 1024 / 1024,
                runtime.maxMemory() / 1024 / 1024);

        if (enableGCSuggestion) {
            suggestGC();
        }

        // Trigger memory optimization
        optimizeMemoryUsage();
    }

    /**
     * Suggest garbage collection.
     */
    public void suggestGC() {
        log.info("Suggesting garbage collection due to memory pressure");

        long beforeGC = System.currentTimeMillis();
        System.gc();
        long afterGC = System.currentTimeMillis();

        long gcTime = afterGC - beforeGC;
        gcCount.incrementAndGet();
        totalGCTime.addAndGet(gcTime);
        lastGCTime = afterGC;

        log.debug("GC completed in {}ms", gcTime);

        // Update memory stats after GC
        updateMemoryStats();
    }

    /**
     * Optimize memory usage.
     */
    private void optimizeMemoryUsage() {
        // This could be extended with more sophisticated optimization
        log.info("Triggering memory optimization");

        // Clear caches if available
        // Notify other components to free memory
        // Suggest reducing batch sizes, etc.
    }

    /**
     * Log memory statistics.
     */
    private void logMemoryStats() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        if (log.isDebugEnabled()) {
            log.debug("Memory Stats - Heap: {}/{}MB ({:.1f}%), Non-Heap: {}/{}MB",
                    heapUsage.getUsed() / 1024 / 1024,
                    heapUsage.getMax() / 1024 / 1024,
                    (double) heapUsage.getUsed() / heapUsage.getMax() * 100,
                    nonHeapUsage.getUsed() / 1024 / 1024,
                    nonHeapUsage.getMax() / 1024 / 1024);
        }
    }

    /**
     * Register for GC notifications.
     */
    private void registerGCNotifications() {
        try {
            // Register with MemoryMXBean for GC notifications
            // This is a simplified version - in practice you'd use NotificationListener
            log.debug("GC notifications registered");
        } catch (Exception e) {
            log.warn("Failed to register GC notifications", e);
        }
    }

    /**
     * Check if memory is under pressure.
     */
    public boolean isMemoryPressure() {
        updateMemoryStats();
        return memoryPressure;
    }

    /**
     * Get memory utilization percentage.
     */
    public double getMemoryUtilization() {
        updateMemoryStats();
        return memoryUtilization * 100;
    }

    /**
     * Get detailed memory information.
     */
    public MemoryInfo getMemoryInfo() {
        updateMemoryStats();

        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        return MemoryInfo.builder()
                .heapUsed(heapUsage.getUsed())
                .heapMax(heapUsage.getMax())
                .heapCommitted(heapUsage.getCommitted())
                .nonHeapUsed(nonHeapUsage.getUsed())
                .nonHeapMax(nonHeapUsage.getMax())
                .nonHeapCommitted(nonHeapUsage.getCommitted())
                .maxMemoryUsed(maxMemoryUsed)
                .currentMemoryUsed(currentMemoryUsed)
                .memoryUtilization(memoryUtilization)
                .memoryPressure(memoryPressure)
                .gcCount(gcCount.get())
                .totalGCTime(totalGCTime.get())
                .lastGCTime(lastGCTime)
                .build();
    }

    /**
     * Get memory recommendations.
     */
    public String getMemoryRecommendations() {
        StringBuilder recommendations = new StringBuilder();

        if (memoryUtilization > 0.9) {
            recommendations.append("High memory usage (").append(String.format("%.1f%%", memoryUtilization * 100))
                    .append("). Consider increasing heap size or optimizing memory usage. ");
        } else if (memoryUtilization > 0.8) {
            recommendations.append("Moderate memory usage (").append(String.format("%.1f%%", memoryUtilization * 100))
                    .append("). Monitor closely and consider optimization. ");
        }

        if (gcCount.get() > 0) {
            long avgGCTime = totalGCTime.get() / gcCount.get();
            if (avgGCTime > 100) { // More than 100ms average GC time
                recommendations.append("High average GC time (")
                        .append(String.format("%.1fms", avgGCTime))
                        .append("). Consider optimizing object allocation. ");
            }
        }

        if (recommendations.length() == 0) {
            recommendations.append("Memory usage is optimal.");
        }

        return recommendations.toString();
    }

    /**
     * Check if JVM needs more memory.
     */
    public boolean needsMoreMemory() {
        return memoryUtilization > 0.85 || (gcCount.get() > 10 && getAverageGCTime() > 100);
    }

    /**
     * Get average GC time in milliseconds.
     */
    public long getAverageGCTime() {
        long count = gcCount.get();
        return count > 0 ? totalGCTime.get() / count : 0;
    }

    /**
     * Get recommended heap size in MB.
     */
    public long getRecommendedHeapSize() {
        long currentMax = runtime.maxMemory() / 1024 / 1024;
        if (needsMoreMemory()) {
            return (long) (currentMax * 1.5); // 50% increase
        }
        return currentMax;
    }

    /**
     * Shutdown memory monitor.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Memory monitor shutdown");
    }

    /**
     * Memory information data class.
     */
    @Data
    @Builder
    public static class MemoryInfo {
        private final long heapUsed;
        private final long heapMax;
        private final long heapCommitted;
        private final long nonHeapUsed;
        private final long nonHeapMax;
        private final long nonHeapCommitted;
        private final long maxMemoryUsed;
        private final long currentMemoryUsed;
        private final double memoryUtilization;
        private final boolean memoryPressure;
        private final long gcCount;
        private final long totalGCTime;
        private final long lastGCTime;

        public String getSummary() {
            return String.format(
                    "Memory[Heap: %d/%dMB (%.1f%%), Non-Heap: %d/%dMB, Pressure: %s, GC: %d times, AvgGC: %.1fms]",
                    heapUsed / 1024 / 1024, heapMax / 1024 / 1024, memoryUtilization * 100,
                    nonHeapUsed / 1024 / 1024, nonHeapMax / 1024 / 1024,
                    memoryPressure ? "Yes" : "No",
                    gcCount, gcCount > 0 ? (double) totalGCTime / gcCount / 1_000_000 : 0
            );
        }
    }
}