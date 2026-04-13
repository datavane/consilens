package com.consilens.performance;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * System resource monitor for tracking CPU, memory, disk I/O, and thread usage.
 * Provides real-time monitoring and statistical aggregation.
 */
@Slf4j
public class ResourceMonitor {

    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final RuntimeMXBean runtimeBean;

    private final ScheduledExecutorService scheduler;
    private volatile boolean monitoring = false;

    // CPU metrics
    private final AtomicLong cpuSampleCount = new AtomicLong(0);
    private volatile double totalCpuUsage = 0.0;
    private volatile double peakCpuUsage = 0.0;
    private volatile double totalSystemLoad = 0.0;

    // Memory metrics
    private volatile long peakHeapUsage = 0;
    private volatile long peakNonHeapUsage = 0;
    private final AtomicLong memorySampleCount = new AtomicLong(0);
    private volatile long totalHeapUsage = 0;

    // Thread metrics
    private volatile int peakThreadCount = 0;
    private final AtomicLong threadSampleCount = new AtomicLong(0);
    private volatile long totalThreadCount = 0;

    // Disk I/O metrics (platform-specific)
    private volatile long diskReadBytes = 0;
    private volatile long diskWriteBytes = 0;
    private volatile long diskReadOps = 0;
    private volatile long diskWriteOps = 0;

    // GC metrics
    private long initialGcCount = 0;
    private long initialGcTime = 0;

    public ResourceMonitor() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ResourceMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start monitoring resources at the specified interval.
     */
    public void startMonitoring(long intervalMs) {
        if (monitoring) {
            log.warn("Resource monitoring is already running");
            return;
        }

        monitoring = true;
        resetMetrics();
        captureInitialGcMetrics();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                collectMetrics();
            } catch (Exception e) {
                log.error("Error collecting resource metrics", e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.info("Resource monitoring started with interval: {} ms", intervalMs);
    }

    /**
     * Stop monitoring resources.
     */
    public void stopMonitoring() {
        monitoring = false;
        log.info("Resource monitoring stopped");
    }

    /**
     * Shutdown the resource monitor.
     */
    public void shutdown() {
        stopMonitoring();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reset all metrics.
     */
    private void resetMetrics() {
        cpuSampleCount.set(0);
        totalCpuUsage = 0.0;
        peakCpuUsage = 0.0;
        totalSystemLoad = 0.0;

        peakHeapUsage = 0;
        peakNonHeapUsage = 0;
        memorySampleCount.set(0);
        totalHeapUsage = 0;

        peakThreadCount = 0;
        threadSampleCount.set(0);
        totalThreadCount = 0;

        diskReadBytes = 0;
        diskWriteBytes = 0;
        diskReadOps = 0;
        diskWriteOps = 0;
    }

    /**
     * Capture initial GC metrics for delta calculation.
     */
    private void captureInitialGcMetrics() {
        GcMetrics gc = getGcMetrics();
        initialGcCount = gc.count;
        initialGcTime = gc.timeMs;
    }

    /**
     * Collect current resource metrics.
     */
    private void collectMetrics() {
        collectCpuMetrics();
        collectMemoryMetrics();
        collectThreadMetrics();
        collectDiskMetrics();
    }

    /**
     * Collect CPU metrics.
     */
    private void collectCpuMetrics() {
        double cpuUsage = getCpuUsage();
        if (cpuUsage >= 0) {
            cpuSampleCount.incrementAndGet();
            totalCpuUsage += cpuUsage;
            peakCpuUsage = Math.max(peakCpuUsage, cpuUsage);
        }

        double systemLoad = osBean.getSystemLoadAverage();
        if (systemLoad >= 0) {
            totalSystemLoad += systemLoad;
        }
    }

    /**
     * Collect memory metrics.
     */
    private void collectMemoryMetrics() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long heapUsed = heapUsage.getUsed();

        memorySampleCount.incrementAndGet();
        totalHeapUsage += heapUsed;
        peakHeapUsage = Math.max(peakHeapUsage, heapUsed);

        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        peakNonHeapUsage = Math.max(peakNonHeapUsage, nonHeapUsage.getUsed());
    }

    /**
     * Collect thread metrics.
     */
    private void collectThreadMetrics() {
        int threadCount = threadBean.getThreadCount();
        threadSampleCount.incrementAndGet();
        totalThreadCount += threadCount;
        peakThreadCount = Math.max(peakThreadCount, threadCount);
    }

    /**
     * Collect disk I/O metrics (platform-specific).
     */
    private void collectDiskMetrics() {
        // Platform-specific disk I/O collection
        // This is a simplified version - actual implementation would use JNI or
        // platform-specific APIs
        try {
            if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                collectLinuxDiskMetrics();
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                collectMacDiskMetrics();
            }
        } catch (Exception e) {
            log.debug("Could not collect disk metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect disk metrics on Linux by reading /proc/diskstats.
     */
    private void collectLinuxDiskMetrics() {
        try {
            Process process = Runtime.getRuntime().exec("cat /proc/diskstats");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long readSectors = 0;
                long writeSectors = 0;

                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 14) {
                        // Fields 5 and 9 contain read and write sectors
                        readSectors += Long.parseLong(parts[5]);
                        writeSectors += Long.parseLong(parts[9]);
                    }
                }

                // Convert sectors to bytes (assuming 512 bytes per sector)
                diskReadBytes = readSectors * 512;
                diskWriteBytes = writeSectors * 512;
            }
        } catch (Exception e) {
            log.debug("Error reading Linux disk stats: {}", e.getMessage());
        }
    }

    /**
     * Collect disk metrics on macOS using iostat.
     */
    private void collectMacDiskMetrics() {
        try {
            Process process = Runtime.getRuntime().exec("iostat -d");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("disk")) {
                        // Parse iostat output
                        // This is simplified - actual parsing would be more robust
                        log.debug("iostat line: {}", line);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error reading macOS disk stats: {}", e.getMessage());
        }
    }

    /**
     * Get current CPU usage percentage.
     */
    private double getCpuUsage() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            return sunOsBean.getProcessCpuLoad() * 100.0;
        }
        return -1.0;
    }

    /**
     * Get GC metrics.
     */
    private GcMetrics getGcMetrics() {
        long totalGcCount = 0;
        long totalGcTime = 0;

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();

            if (count >= 0) {
                totalGcCount += count;
            }
            if (time >= 0) {
                totalGcTime += time;
            }
        }

        return new GcMetrics(totalGcCount, totalGcTime);
    }

    /**
     * Get resource metrics snapshot.
     */
    public PerformanceMetrics.ResourceMetrics getResourceMetrics() {
        PerformanceMetrics.ResourceMetrics metrics = new PerformanceMetrics.ResourceMetrics();

        // Memory metrics
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        metrics.setInitialHeapMB(heapUsage.getInit() / (1024 * 1024));
        metrics.setFinalHeapMB(heapUsage.getUsed() / (1024 * 1024));
        metrics.setPeakHeapMB(peakHeapUsage / (1024 * 1024));

        long avgHeap = memorySampleCount.get() > 0 ? totalHeapUsage / memorySampleCount.get() : heapUsage.getUsed();
        metrics.setAvgHeapMB(avgHeap / (1024 * 1024));

        metrics.setInitialNonHeapMB(nonHeapUsage.getInit() / (1024 * 1024));
        metrics.setFinalNonHeapMB(nonHeapUsage.getUsed() / (1024 * 1024));
        metrics.setPeakNonHeapMB(peakNonHeapUsage / (1024 * 1024));

        // GC metrics
        GcMetrics currentGc = getGcMetrics();
        metrics.setGcCount(currentGc.count - initialGcCount);
        metrics.setGcTimeMs(currentGc.timeMs - initialGcTime);
        metrics.setAvgGcTimeMs(metrics.getGcCount() > 0 ? (double) metrics.getGcTimeMs() / metrics.getGcCount() : 0.0);

        // CPU metrics
        long cpuSamples = cpuSampleCount.get();
        metrics.setAvgCpuUsagePercent(cpuSamples > 0 ? totalCpuUsage / cpuSamples : 0.0);
        metrics.setPeakCpuUsagePercent(peakCpuUsage);
        metrics.setAvgSystemLoadAverage(cpuSamples > 0 ? totalSystemLoad / cpuSamples : 0.0);

        // Disk I/O metrics
        metrics.setDiskReadBytes(diskReadBytes);
        metrics.setDiskWriteBytes(diskWriteBytes);
        metrics.setDiskReadOps(diskReadOps);
        metrics.setDiskWriteOps(diskWriteOps);

        // Thread metrics
        metrics.setPeakThreadCount(peakThreadCount);
        long threadSamples = threadSampleCount.get();
        metrics.setAvgThreadCount(
                threadSamples > 0 ? (int) (totalThreadCount / threadSamples) : threadBean.getThreadCount());

        return metrics;
    }

    /**
     * GC metrics holder.
     */
    private static class GcMetrics {
        final long count;
        final long timeMs;

        GcMetrics(long count, long timeMs) {
            this.count = count;
            this.timeMs = timeMs;
        }
    }
}
