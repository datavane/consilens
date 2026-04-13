package com.consilens.performance;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adaptive thread pool executor that adjusts thread pool size based on workload.
 * Provides dynamic scaling, performance monitoring, and automatic optimization.
 */
@Slf4j
public class AdaptiveThreadPoolExecutor {

    private final ThreadPoolExecutor executor;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger submittedTasks = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger rejectedTasks = new AtomicInteger(0);

    private final int minPoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final BlockingQueue<Runnable> workQueue;

    // Performance tracking
    private volatile long lastAdjustmentTime;
    private volatile double avgExecutionTime;
    private volatile long lastOptimizationCheck;

    public AdaptiveThreadPoolExecutor(int minPoolSize, int maxPoolSize, long keepAliveTime, TimeUnit timeUnit) {
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.workQueue = new LinkedBlockingQueue<>();

        this.executor = createThreadPool();
        this.lastAdjustmentTime = System.currentTimeMillis();
        this.avgExecutionTime = 0;
        this.lastOptimizationCheck = System.currentTimeMillis();

        startPerformanceMonitoring();
    }

    /**
     * Create adaptive thread pool with default configuration.
     */
    public AdaptiveThreadPoolExecutor() {
        this(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors() * 4,
                60L, TimeUnit.SECONDS
        );
    }

    /**
     * Create the underlying thread pool executor.
     */
    private ThreadPoolExecutor createThreadPool() {
        ThreadFactory threadFactory = new AdaptiveThreadFactory();
        RejectedExecutionHandler rejectedHandler = new AdaptiveRejectedExecutionHandler();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                minPoolSize,
                maxPoolSize,
                keepAliveTime,
                timeUnit,
                workQueue,
                threadFactory,
                rejectedHandler
        );

        // Allow core threads to timeout
        executor.allowCoreThreadTimeOut(true);

        return executor;
    }

    /**
     * Submit task with performance tracking.
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        submittedTasks.incrementAndGet();
        activeTasks.incrementAndGet();

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            try {
                T result = task.call();
                long executionTime = System.nanoTime() - startTime;
                updateExecutionMetrics(executionTime);
                return result;
            } catch (Exception e) {
                log.error("Task execution failed", e);
                throw new RuntimeException(e);
            } finally {
                activeTasks.decrementAndGet();
                completedTasks.incrementAndGet();
            }
        }, executor);

        future.whenComplete((result, error) -> {
            if (error != null) {
                log.warn("Task completed with error", error);
            }
        });

        // Trigger pool size adjustment if needed
        considerPoolSizeAdjustment();

        return future;
    }

    /**
     * Submit runnable task.
     */
    public CompletableFuture<Void> submit(Runnable task) {
        return submit(Executors.callable(task, null));
    }

    /**
     * Execute task with timeout.
     */
    public <T> CompletableFuture<T> submitWithTimeout(Callable<T> task, long timeout, TimeUnit unit) {
        CompletableFuture<T> future = submit(task);
        return future.orTimeout(timeout, unit);
    }

    /**
     * Update execution metrics.
     */
    private void updateExecutionMetrics(long executionTime) {
        // Update average execution time using exponential moving average
        if (avgExecutionTime == 0) {
            avgExecutionTime = executionTime;
        } else {
            avgExecutionTime = 0.9 * avgExecutionTime + 0.1 * executionTime;
        }
    }

    /**
     * Consider adjusting pool size based on current workload.
     */
    private void considerPoolSizeAdjustment() {
        long now = System.currentTimeMillis();
        if (now - lastAdjustmentTime < 5000) { // Don't adjust more frequently than every 5 seconds
            return;
        }

        int currentPoolSize = executor.getPoolSize();
        int activeCount = activeTasks.get();
        int queueSize = workQueue.size();

        // Increase pool size if queue is building up and we're not at max
        if (queueSize > 10 && currentPoolSize < maxPoolSize && activeCount >= currentPoolSize) {
            int newSize = Math.min(currentPoolSize + 2, maxPoolSize);
            executor.setCorePoolSize(newSize);
            log.info("Increased thread pool size from {} to {}", currentPoolSize, newSize);
            lastAdjustmentTime = now;
            return;
        }

        // Decrease pool size if underutilized
        if (queueSize == 0 && activeCount < currentPoolSize * 0.5 && currentPoolSize > minPoolSize) {
            int newSize = Math.max(currentPoolSize - 1, minPoolSize);
            executor.setCorePoolSize(newSize);
            log.info("Decreased thread pool size from {} to {}", currentPoolSize, newSize);
            lastAdjustmentTime = now;
        }
    }

    /**
     * Start performance monitoring.
     */
    private void startPerformanceMonitoring() {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ThreadPoolMonitor");
            t.setDaemon(true);
            return t;
        });

        monitor.scheduleAtFixedRate(this::logPerformanceMetrics, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Log performance metrics.
     */
    private void logPerformanceMetrics() {
        int poolSize = executor.getPoolSize();
        int activeThreads = executor.getActiveCount();
        int queueSize = workQueue.size();
        long completed = completedTasks.get();
        long submitted = submittedTasks.get();

        double avgExecutionMs = avgExecutionTime / 1_000_000.0;

        log.info("ThreadPool Stats - Size: {}, Active: {}, Queue: {}, " +
                "Completed: {}, Submitted: {}, Avg Execution: {}ms",
                poolSize, activeThreads, queueSize, completed, submitted, avgExecutionMs);

        // Check for performance issues
        checkPerformanceHealth(poolSize, activeThreads, queueSize, avgExecutionMs);
    }

    /**
     * Check for performance health issues.
     */
    private void checkPerformanceHealth(int poolSize, int activeThreads, int queueSize, double avgExecutionMs) {
        // High queue size indicates thread pool is undersized
        if (queueSize > 50) {
            log.warn("High queue size ({}). Consider increasing thread pool size.", queueSize);
        }

        // Low utilization could indicate oversized pool
        if (poolSize > minPoolSize && activeThreads < poolSize * 0.3 && queueSize == 0) {
            log.info("Low thread utilization ({} / {}). Consider reducing pool size.", activeThreads, poolSize);
        }

        // High average execution time
        if (avgExecutionMs > 1000) { // More than 1 second
            log.warn("High average execution time ({:.2f}ms). Check for blocking operations.", avgExecutionMs);
        }

        // High rejection rate
        long rejected = rejectedTasks.get();
        long submitted = submittedTasks.get();
        if (submitted > 100 && (double) rejected / submitted > 0.01) { // More than 1% rejection rate
            log.warn("High rejection rate ({}/{} = {:.2f}%). Consider increasing max pool size.",
                    rejected, submitted, (double) rejected / submitted * 100);
        }
    }

    /**
     * Get thread pool statistics.
     */
    public ThreadPoolStats getStats() {
        return new ThreadPoolStats(
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                workQueue.size(),
                executor.getTaskCount(),
                executor.getCompletedTaskCount(),
                activeTasks.get(),
                submittedTasks.get(),
                completedTasks.get(),
                rejectedTasks.get(),
                avgExecutionTime / 1_000_000.0 // Convert to milliseconds
        );
    }

    /**
     * Get performance recommendations.
     */
    public String getPerformanceRecommendations() {
        ThreadPoolStats stats = getStats();
        StringBuilder recommendations = new StringBuilder();

        // Queue size recommendations
        if (stats.getQueueSize() > 20) {
            recommendations.append("Large queue size (").append(stats.getQueueSize())
                    .append("). Consider increasing max pool size or using async processing. ");
        }

        // Thread utilization recommendations
        double utilization = (double) stats.getActiveThreads() / stats.getCurrentPoolSize();
        if (utilization < 0.3 && stats.getCurrentPoolSize() > minPoolSize) {
            recommendations.append("Low thread utilization (").append(String.format("%.1f%%", utilization * 100))
                    .append("). Consider reducing pool size to ").append(Math.max(minPoolSize, stats.getCurrentPoolSize() / 2))
                    .append(". ");
        }

        // Execution time recommendations
        if (stats.getAverageExecutionTime() > 500) { // More than 500ms
            recommendations.append("High average execution time (")
                    .append(String.format("%.1fms", stats.getAverageExecutionTime()))
                    .append("). Consider optimizing task implementation or using non-blocking operations. ");
        }

        // Rejection rate recommendations
        long rejectionRate = stats.getSubmittedTasks() > 0 ?
                (stats.getRejectedTasks() * 100 / stats.getSubmittedTasks()) : 0;
        if (rejectionRate > 5) {
            recommendations.append("High rejection rate (").append(rejectionRate)
                    .append("%). Increase max pool size or reduce task submission rate. ");
        }

        if (recommendations.length() == 0) {
            recommendations.append("Thread pool performance is optimal.");
        }

        return recommendations.toString();
    }

    /**
     * Shutdown the thread pool gracefully.
     */
    public void shutdown() {
        log.info("Shutting down adaptive thread pool executor");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Thread pool did not terminate within 30 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Get the underlying executor.
     */
    public ThreadPoolExecutor getExecutor() {
        return executor;
    }

    /**
     * Custom thread factory for adaptive thread pool.
     */
    private static class AdaptiveThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "adaptive-pool-" + threadNumber.getAndIncrement());
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    /**
     * Custom rejected execution handler.
     */
    private static class AdaptiveRejectedExecutionHandler implements RejectedExecutionHandler {
        private final AtomicInteger rejectedCount = new AtomicInteger(0);

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            int count = rejectedCount.incrementAndGet();
            log.warn("Task rejected (count: {}). Pool size: {}, Queue size: {}, Active: {}",
                    count, executor.getPoolSize(), executor.getQueue().size(), executor.getActiveCount());

            // Try to run in the calling thread
            try {
                r.run();
            } catch (Exception e) {
                log.error("Failed to execute rejected task in caller thread", e);
                throw new RejectedExecutionException("Task execution failed", e);
            }
        }

        public int getRejectedCount() {
            return rejectedCount.get();
        }
    }

    /**
     * Thread pool statistics data class.
     */
    @Data
    public static class ThreadPoolStats {
        private final int corePoolSize;
        private final int maximumPoolSize;
        private final int currentPoolSize;
        private final int activeThreads;
        private final int queueSize;
        private final long taskCount;
        private final long completedTaskCount;
        private final int activeTasks;
        private final int submittedTasks;
        private final int completedTasks;
        private final int rejectedTasks;
        private final double averageExecutionTime;

        public ThreadPoolStats(int corePoolSize, int maximumPoolSize, int currentPoolSize,
                             int activeThreads, int queueSize, long taskCount, long completedTaskCount,
                             int activeTasks, int submittedTasks, int completedTasks,
                             int rejectedTasks, double averageExecutionTime) {
            this.corePoolSize = corePoolSize;
            this.maximumPoolSize = maximumPoolSize;
            this.currentPoolSize = currentPoolSize;
            this.activeThreads = activeThreads;
            this.queueSize = queueSize;
            this.taskCount = taskCount;
            this.completedTaskCount = completedTaskCount;
            this.activeTasks = activeTasks;
            this.submittedTasks = submittedTasks;
            this.completedTasks = completedTasks;
            this.rejectedTasks = rejectedTasks;
            this.averageExecutionTime = averageExecutionTime;
        }

        public String getSummary() {
            return String.format(
                    "ThreadPool[Core: %d, Max: %d, Current: %d, Active: %d, Queue: %d, " +
                    "AvgTime: %.1fms, Rejected: %d]",
                    corePoolSize, maximumPoolSize, currentPoolSize, activeThreads,
                    queueSize, averageExecutionTime, rejectedTasks
            );
        }
    }
}