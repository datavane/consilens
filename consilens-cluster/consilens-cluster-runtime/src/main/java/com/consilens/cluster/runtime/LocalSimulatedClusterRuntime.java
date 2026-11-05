package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * In-process implementation of the Coordinator/Worker protocol used by Phase 0.
 *
 * <p>任务结果始终按输入顺序合并（与并行度无关），保证 manifest 与差异顺序确定；
 * 并行度取 {@code ClusterExecutionSpec.parallelism}，默认按段数与本机核数取较小值。
 * 生产 YARN / Kubernetes 后端尚不存在，这里的并行只覆盖 in-process 模式。
 */
public class LocalSimulatedClusterRuntime {

    private static final int DEFAULT_MAXIMUM_ATTEMPTS = 1;

    private final LocalManifestPublisher manifestPublisher;

    public LocalSimulatedClusterRuntime() {
        this(new LocalManifestPublisher());
    }

    LocalSimulatedClusterRuntime(LocalManifestPublisher manifestPublisher) {
        this.manifestPublisher = Objects.requireNonNull(manifestPublisher, "manifestPublisher");
    }

    public LocalSimulationResult execute(LocalSimulationRequest request) throws Exception {
        validateRequest(request);

        int maximumAttempts = maximumAttempts(request.getExecutionSpec());
        List<LocalSplitTask> tasks = request.getSplitTasks();
        int parallelism = parallelism(request, tasks.size());
        List<SplitAttemptRecord> attemptRecords = new ArrayList<>();
        List<DiffResult> successfulResults = new ArrayList<>();
        LinkedHashSet<String> successfulSplitIds = new LinkedHashSet<>();

        if (parallelism <= 1 || tasks.size() <= 1) {
            for (LocalSplitTask task : tasks) {
                DiffResult successfulResult = executeSplit(task, maximumAttempts, attemptRecords);
                successfulResults.add(successfulResult);
                successfulSplitIds.add(task.getSplitId());
            }
        } else {
            executeInParallel(tasks, maximumAttempts, parallelism,
                    attemptRecords, successfulResults, successfulSplitIds);
        }

        DiffResult mergedResult = mergeSuccessfulResults(successfulResults);
        List<SplitAttemptRecord> successfulAttemptRecords = new ArrayList<>();
        for (SplitAttemptRecord attemptRecord : attemptRecords) {
            if (attemptRecord.getState() == SplitAttemptState.SUCCEEDED) {
                successfulAttemptRecords.add(attemptRecord);
            }
        }
        LocalExecutionManifest manifest = LocalExecutionManifest.builder()
                .formatVersion("1")
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.LOCAL)
                .attempts(List.copyOf(successfulAttemptRecords))
                .successfulSplitIds(List.copyOf(successfulSplitIds))
                .sourceRowCount(mergedResult.getStatistics().getSourceRowCount())
                .targetRowCount(mergedResult.getStatistics().getTargetRowCount())
                .totalDifferences(mergedResult.getStatistics().getTotalDifferences())
                .build();
        Path manifestPath = manifestPublisher.publish(request.getManifestDirectory(), manifest);
        return LocalSimulationResult.builder()
                .diffResult(mergedResult)
                .manifestPath(manifestPath)
                .attempts(List.copyOf(attemptRecords))
                .build();
    }

    /**
     * 有界并行执行各段：结果与 attempt 记录仍按输入顺序汇总，失败段在轮到它时抛出，
     * 与串行语义一致（manifest 不会发布）。
     */
    private void executeInParallel(List<LocalSplitTask> tasks,
                                   int maximumAttempts,
                                   int parallelism,
                                   List<SplitAttemptRecord> attemptRecords,
                                   List<DiffResult> successfulResults,
                                   LinkedHashSet<String> successfulSplitIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()), runnable -> {
            Thread thread = new Thread(runnable, "local-split-executor");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<SplitOutcome>> futures = new ArrayList<>(tasks.size());
            for (LocalSplitTask task : tasks) {
                futures.add(pool.submit(() -> {
                    List<SplitAttemptRecord> records = new ArrayList<>();
                    DiffResult result = executeSplit(task, maximumAttempts, records);
                    return new SplitOutcome(task.getSplitId(), result, records);
                }));
            }
            for (Future<SplitOutcome> future : futures) {
                SplitOutcome outcome;
                try {
                    outcome = future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw e;
                }
                attemptRecords.addAll(outcome.attempts);
                successfulResults.add(outcome.result);
                successfulSplitIds.add(outcome.splitId);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int parallelism(LocalSimulationRequest request, int splitCount) {
        Integer configured = request.getExecutionSpec().getParallelism();
        if (configured != null) {
            return Math.max(1, configured);
        }
        return Math.max(1, Math.min(splitCount, Runtime.getRuntime().availableProcessors()));
    }

    private static final class SplitOutcome {
        private final String splitId;
        private final DiffResult result;
        private final List<SplitAttemptRecord> attempts;

        private SplitOutcome(String splitId, DiffResult result, List<SplitAttemptRecord> attempts) {
            this.splitId = splitId;
            this.result = result;
            this.attempts = attempts;
        }
    }

    private DiffResult executeSplit(LocalSplitTask task,
                                    int maximumAttempts,
                                    List<SplitAttemptRecord> attemptRecords) throws Exception {
        Throwable lastFailure = null;
        for (int attemptNumber = 1; attemptNumber <= maximumAttempts; attemptNumber++) {
            SplitAttempt attempt = new SplitAttempt(task.getSplitId(), attemptNumber);
            attempt.start();
            try {
                DiffResult result = task.execute();
                if (result == null) {
                    throw new IllegalStateException("Split task returned no DiffResult");
                }
                attempt.succeed();
                attemptRecords.add(toRecord(attempt));
                return result;
            } catch (Exception e) {
                attempt.fail(e);
                attemptRecords.add(toRecord(attempt));
                lastFailure = e;
            }
        }
        throw new LocalClusterExecutionException("Split " + task.getSplitId()
                + " failed after " + maximumAttempts + " attempt(s)", lastFailure);
    }

    private SplitAttemptRecord toRecord(SplitAttempt attempt) {
        return SplitAttemptRecord.builder()
                .splitId(attempt.getSplitId())
                .attemptNumber(attempt.getAttemptNumber())
                .state(attempt.getState())
                .failureMessage(attempt.getFailureMessage())
                .build();
    }

    private void validateRequest(LocalSimulationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getSubmissionId() == null || request.getSubmissionId().trim().isEmpty()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        if (request.getExecutionSpec() == null
                || request.getExecutionSpec().getExecutionMode() != ExecutionMode.LOCAL) {
            throw new IllegalArgumentException("Local simulated runtime only supports LOCAL execution mode");
        }
        if (request.getSplitTasks() == null || request.getSplitTasks().isEmpty()) {
            throw new IllegalArgumentException("splitTasks must not be empty");
        }
        if (request.getManifestDirectory() == null) {
            throw new IllegalArgumentException("manifestDirectory must not be null");
        }
        LinkedHashSet<String> splitIds = new LinkedHashSet<>();
        for (LocalSplitTask task : request.getSplitTasks()) {
            if (task == null || task.getSplitId() == null || task.getSplitId().trim().isEmpty()) {
                throw new IllegalArgumentException("each split task must have a splitId");
            }
            if (!splitIds.add(task.getSplitId())) {
                throw new IllegalArgumentException("splitIds must be unique: " + task.getSplitId());
            }
        }
        maximumAttempts(request.getExecutionSpec());
    }

    private int maximumAttempts(ClusterExecutionSpec executionSpec) {
        Integer configured = executionSpec.getMaxAttempts();
        int value = configured == null ? DEFAULT_MAXIMUM_ATTEMPTS : configured;
        if (value < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        return value;
    }

    private DiffResult mergeSuccessfulResults(List<DiffResult> results) {
        List<DiffRow> differences = new ArrayList<>();
        long sourceRowCount = 0;
        long targetRowCount = 0;
        long sourceMissingCount = 0;
        long targetMissingCount = 0;
        long mismatchCount = 0;
        long processingTimeMs = 0;
        long unchangedCount = 0;
        Map<String, Object> metadata = new LinkedHashMap<>();
        DiffResult first = null;
        Instant completedAt = Instant.EPOCH;

        for (DiffResult result : results) {
            if (first == null) {
                first = result;
            }
            if (result.getDifferences() != null) {
                differences.addAll(result.getDifferences());
            }
            DiffResult.DiffStatistics statistics = result.getStatistics();
            if (statistics != null) {
                sourceRowCount += statistics.getSourceRowCount();
                targetRowCount += statistics.getTargetRowCount();
                sourceMissingCount += statistics.getSourceMissingCount();
                targetMissingCount += statistics.getTargetMissingCount();
                mismatchCount += statistics.getMismatchCount();
                processingTimeMs += statistics.getProcessingTimeMs();
                unchangedCount += statistics.getUnchangedCount();
            }
            if (result.getMetadata() != null) {
                metadata.putAll(result.getMetadata());
            }
            if (result.getCompletedAt() != null && result.getCompletedAt().isAfter(completedAt)) {
                completedAt = result.getCompletedAt();
            }
        }

        if (first == null) {
            throw new IllegalArgumentException("results must not be empty");
        }
        return DiffResult.builder()
                .differences(differences)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRowCount)
                        .targetRowCount(targetRowCount)
                        .sourceMissingCount(sourceMissingCount)
                        .targetMissingCount(targetMissingCount)
                        .mismatchCount(mismatchCount)
                        .totalDifferences(sourceMissingCount + targetMissingCount + mismatchCount)
                        .processingTimeMs(processingTimeMs)
                        .unchangedCount(unchangedCount)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(completedAt.equals(Instant.EPOCH) ? Instant.now() : completedAt)
                .metadata(metadata)
                .sourceTablePath(first.getSourceTablePath())
                .targetTablePath(first.getTargetTablePath())
                .build();
    }
}
