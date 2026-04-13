package com.consilens.performance;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Async task orchestrator for coordinating complex asynchronous workflows.
 * Provides pipeline processing, retry mechanisms, and performance monitoring.
 */
@Slf4j
public class AsyncTaskOrchestrator {

    private final Executor executor;
    private final RetryPolicy defaultRetryPolicy;
    private final ConcurrentHashMap<String, TaskMetrics> taskMetrics;
    private final AtomicLong taskIdGenerator;

    public AsyncTaskOrchestrator(Executor executor) {
        this(executor, RetryPolicy.defaultPolicy());
    }

    public AsyncTaskOrchestrator(Executor executor, RetryPolicy retryPolicy) {
        this.executor = executor;
        this.defaultRetryPolicy = retryPolicy;
        this.taskMetrics = new ConcurrentHashMap<>();
        this.taskIdGenerator = new AtomicLong(0);
    }

    /**
     * Execute task with retry mechanism.
     */
    public <T> CompletableFuture<T> executeWithRetry(String taskName, Supplier<T> task) {
        return executeWithRetry(taskName, task, defaultRetryPolicy);
    }

    /**
     * Execute task with custom retry policy.
     */
    public <T> CompletableFuture<T> executeWithRetry(String taskName, Supplier<T> task, RetryPolicy retryPolicy) {
        String taskId = generateTaskId(taskName);
        TaskMetrics metrics = getOrCreateTaskMetrics(taskId);

        long startTime = System.currentTimeMillis();
        metrics.incrementSubmitted();

        return executeWithRetryInternal(task, retryPolicy, taskId, 0, startTime)
                .whenComplete((result, error) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (error == null) {
                        metrics.recordSuccess(duration);
                        log.debug("Task {} completed successfully in {}ms", taskName, duration);
                    } else {
                        metrics.recordFailure(duration);
                        log.warn("Task {} failed after {}ms", taskName, duration, error);
                    }
                });
    }

    /**
     * Internal retry execution logic.
     */
    private <T> CompletableFuture<T> executeWithRetryInternal(
            Supplier<T> task, RetryPolicy retryPolicy, String taskId, int attempt, long startTime) {

        if (attempt >= retryPolicy.getMaxAttempts()) {
            throw new RuntimeException("Task " + taskId + " failed after " + attempt + " attempts");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("Executing task {} (attempt {})", taskId, attempt);
                return task.get();
            } catch (Exception e) {
                log.warn("Task {} failed on attempt {}: {}", taskId, attempt, e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor)
        .exceptionally(throwable -> {
            Exception cause = (Exception) throwable.getCause();

            if (shouldRetry(retryPolicy, cause, attempt)) {
                long delay = calculateRetryDelay(retryPolicy, attempt);
                log.info("Retrying task {} in {}ms (attempt {}/{})", taskId, delay, attempt + 1, retryPolicy.getMaxAttempts());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry delay", e);
                }

                // Recursive retry call
                return executeWithRetryInternal(task, retryPolicy, taskId, attempt + 1, startTime).join();
            } else {
                log.error("Task {} failed after {} attempts", taskId, attempt + 1, cause);
                throw new RuntimeException("Task failed after " + (attempt + 1) + " attempts", cause);
            }
        });
    }

    /**
     * Execute task pipeline.
     */
    public CompletableFuture<DiffResult> executeDiffPipeline(
            TableSegment table1, TableSegment table2) {

        String taskId = generateTaskId("diff-pipeline");
        long startTime = System.currentTimeMillis();

        // Stage 1: Data preparation
        CompletableFuture<TableSegment> stage1 = executeWithRetry("prepare-table1",
                () -> prepareTableData(table1));

        CompletableFuture<TableSegment> stage2 = executeWithRetry("prepare-table2",
                () -> prepareTableData(table2));

        // Stage 2: Data validation
        CompletableFuture<Void> validationStage = stage1.thenComposeAsync(t1 ->
                stage2.thenComposeAsync(t2 -> executeWithRetry("validate-tables",
                        () -> {
                            validateTables(t1, t2);
                            return null;
                        })), executor);

        // Stage 3: Difference calculation
        CompletableFuture<DiffResult> diffStage = validationStage.thenComposeAsync(v -> {
            return stage1.thenComposeAsync(t1 -> {
                return stage2.thenComposeAsync(t2 -> {
                    return executeWithRetry("calculate-differences",
                            () -> calculateDifferences(t1, t2));
                }, executor);
            }, executor);
        }, executor);

        // Stage 4: Result post-processing
        CompletableFuture<DiffResult> resultStage = diffStage.thenApply(result -> {
            DiffResult processedResult = postProcessResult(result);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("Diff pipeline completed in {}ms", totalDuration);
            return processedResult;
        });

        return resultStage;
    }

    /**
     * Execute multiple tasks in parallel and collect results.
     */
    public <T, R> CompletableFuture<List<R>> executeParallel(
            List<T> items, Function<T, CompletableFuture<R>> taskFunction) {

        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        log.debug("Executing {} tasks in parallel", items.size());

        List<CompletableFuture<R>> futures = new ArrayList<>();
        for (T item : items) {
            futures.add(taskFunction.apply(item));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<R> results = new ArrayList<>();
                    for (CompletableFuture<R> future : futures) {
                        results.add(future.join());
                    }
                    return results;
                });
    }

    /**
     * Execute batch processing with async results.
     */
    public <T, R> CompletableFuture<List<R>> executeBatchAsync(
            List<T> items, Function<T, CompletableFuture<R>> processor, int batchSize) {

        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        log.debug("Executing batch processing with {} items in batches of {}", items.size(), batchSize);

        List<CompletableFuture<List<R>>> batchFutures = new ArrayList<>();

        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            List<T> batch = items.subList(i, end);

            CompletableFuture<List<R>> batchFuture = executeParallel(batch, processor);
            batchFutures.add(batchFuture);
        }

        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<R> allResults = new ArrayList<>();
                    for (CompletableFuture<List<R>> batchFuture : batchFutures) {
                        allResults.addAll(batchFuture.join());
                    }
                    return allResults;
                });
    }

    /**
     * Execute task with timeout.
     */
    public <T> CompletableFuture<T> executeWithTimeout(String taskName, Supplier<T> task, long timeout, TimeUnit unit) {
        String taskId = generateTaskId(taskName);

        return executeWithRetry(taskId, task)
                .orTimeout(timeout, unit)
                .whenComplete((result, error) -> {
                    if (error != null && error instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Task {} timed out after {} {}", taskName, timeout, unit);
                    }
                });
    }

    /**
     * Monitor task execution and record metrics.
     */
    public <T> CompletableFuture<T> monitorTask(String taskName, CompletableFuture<T> future) {
        String taskId = generateTaskId(taskName);
        long startTime = System.currentTimeMillis();

        return future.whenComplete((result, error) -> {
            long duration = System.currentTimeMillis() - startTime;
            TaskMetrics metrics = getOrCreateTaskMetrics(taskId);

            if (error == null) {
                metrics.recordSuccess(duration);
                log.debug("Monitored task {} completed successfully in {}ms", taskName, duration);
            } else {
                metrics.recordFailure(duration);
                log.warn("Monitored task {} failed in {}ms", taskName, duration, error);
            }
        });
    }

    /**
     * Check if retry should be attempted.
     */
    private boolean shouldRetry(RetryPolicy policy, Exception cause, int attempt) {
        if (attempt >= policy.getMaxAttempts()) {
            return false;
        }

        if (policy.getRetryableExceptions().stream().anyMatch(ex -> ex.isInstance(cause))) {
            return true;
        }

        return policy.isRetryOnAllExceptions();
    }

    /**
     * Calculate retry delay with backoff.
     */
    private long calculateRetryDelay(RetryPolicy policy, int attempt) {
        if (policy.getBackoffMultiplier() > 1.0) {
            return (long) (policy.getInitialDelayMs() * Math.pow(policy.getBackoffMultiplier(), attempt));
        } else {
            return policy.getInitialDelayMs();
        }
    }

    // Placeholder methods for actual implementation
    private TableSegment prepareTableData(TableSegment table) {
        // Implementation would prepare table data for comparison
        return table;
    }

    private void validateTables(TableSegment table1, TableSegment table2) {
        // Implementation would validate table schemas, compatibility, etc.
        if (table1 == null || table2 == null) {
            throw new IllegalArgumentException("Tables cannot be null");
        }
    }

    private DiffResult calculateDifferences(TableSegment table1, TableSegment table2) {
        // Implementation would use appropriate diff engine
        return DiffResult.empty(table1.getTablePath(), table2.getTablePath());
    }

    private DiffResult postProcessResult(DiffResult result) {
        // Implementation would post-process diff results
        return result;
    }

    private String generateTaskId(String taskName) {
        return taskName + "-" + taskIdGenerator.incrementAndGet();
    }

    private TaskMetrics getOrCreateTaskMetrics(String taskId) {
        return taskMetrics.computeIfAbsent(taskId, id -> new TaskMetrics(id));
    }

    /**
     * Get performance metrics for all tasks.
     */
    public List<TaskMetrics> getAllTaskMetrics() {
        return new ArrayList<>(taskMetrics.values());
    }

    /**
     * Get metrics for specific task type.
     */
    public List<TaskMetrics> getTaskMetricsByType(String taskType) {
        return taskMetrics.values().stream()
                .filter(metrics -> metrics.getTaskId().startsWith(taskType))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get overall performance summary.
     */
    public PerformanceSummary getPerformanceSummary() {
        List<TaskMetrics> allMetrics = getAllTaskMetrics();

        long totalSubmitted = allMetrics.stream().mapToLong(TaskMetrics::getSubmittedCount).sum();
        long totalCompleted = allMetrics.stream().mapToLong(TaskMetrics::getCompletedCount).sum();
        long totalFailed = allMetrics.stream().mapToLong(TaskMetrics::getFailedCount).sum();
        double avgExecutionTime = allMetrics.stream()
                .mapToDouble(TaskMetrics::getAverageExecutionTime)
                .average()
                .orElse(0.0);

        return PerformanceSummary.builder()
                .totalSubmitted(totalSubmitted)
                .totalCompleted(totalCompleted)
                .totalFailed(totalFailed)
                .successRate(totalSubmitted > 0 ? (double) totalCompleted / totalSubmitted * 100 : 0.0)
                .averageExecutionTime(avgExecutionTime)
                .taskCount(allMetrics.size())
                .build();
    }

    /**
     * Task metrics data class.
     */
    @lombok.Data
    public static class TaskMetrics {
        private final String taskId;
        private final AtomicLong submittedCount = new AtomicLong(0);
        private final AtomicLong completedCount = new AtomicLong(0);
        private final AtomicLong failedCount = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);

        public TaskMetrics(String taskId) {
            this.taskId = taskId;
        }

        public void incrementSubmitted() {
            submittedCount.incrementAndGet();
        }

        public void recordSuccess(long executionTime) {
            completedCount.incrementAndGet();
            totalExecutionTime.addAndGet(executionTime);
        }

        public void recordFailure(long executionTime) {
            failedCount.incrementAndGet();
            totalExecutionTime.addAndGet(executionTime);
        }

        public long getCompletedCount() {
            return completedCount.get();
        }

        public long getFailedCount() {
            return failedCount.get();
        }

        public long getSubmittedCount() {
            return submittedCount.get();
        }

        public double getAverageExecutionTime() {
            long completed = completedCount.get();
            return completed > 0 ? (double) totalExecutionTime.get() / completed : 0.0;
        }

        public double getSuccessRate() {
            long submitted = submittedCount.get();
            return submitted > 0 ? (double) completedCount.get() / submitted * 100 : 0.0;
        }

        public String getSummary() {
            return String.format(
                    "TaskMetrics[%s] Submitted: %d, Completed: %d, Failed: %d, " +
                    "Success Rate: %.1f%%, Avg Time: %.1fms",
                    taskId, submittedCount.get(), completedCount.get(), failedCount.get(),
                    getSuccessRate(), getAverageExecutionTime()
            );
        }
    }

    /**
     * Performance summary data class.
     */
    @lombok.Data
    @lombok.Builder
    public static class PerformanceSummary {
        private final long totalSubmitted;
        private final long totalCompleted;
        private final long totalFailed;
        private final double successRate;
        private final double averageExecutionTime;
        private final int taskCount;

        public String getSummary() {
            return String.format(
                    "PerformanceSummary[Tasks: %d, Submitted: %d, Completed: %d, Failed: %d, " +
                    "Success Rate: %.1f%%, Avg Time: %.1fms]",
                    taskCount, totalSubmitted, totalCompleted, totalFailed,
                    successRate, averageExecutionTime
            );
        }
    }

    /**
     * Retry policy configuration.
     */
    @lombok.Data
    @lombok.Builder
    public static class RetryPolicy {
        private final int maxAttempts;
        private final long initialDelayMs;
        private final double backoffMultiplier;
        private final boolean retryOnAllExceptions;
        private final List<Class<? extends Exception>> retryableExceptions;

        public static RetryPolicy defaultPolicy() {
            return RetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelayMs(1000)
                    .backoffMultiplier(2.0)
                    .retryOnAllExceptions(false)
                    .retryableExceptions(List.of(
                            java.sql.SQLException.class,
                            java.net.ConnectException.class,
                            java.util.concurrent.TimeoutException.class
                    ))
                    .build();
        }

        public static RetryPolicy noRetry() {
            return RetryPolicy.builder()
                    .maxAttempts(1)
                    .initialDelayMs(0)
                    .backoffMultiplier(1.0)
                    .retryOnAllExceptions(false)
                    .retryableExceptions(List.of())
                    .build();
        }
    }
}