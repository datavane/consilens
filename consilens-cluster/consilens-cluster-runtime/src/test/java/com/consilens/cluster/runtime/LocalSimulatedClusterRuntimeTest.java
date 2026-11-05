package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSimulatedClusterRuntimeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRetryFailedSplitAndOnlyMergeItsSuccessfulAttempt() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        LocalSplitTask retryingTask = task("split-1", () -> {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary failure");
            }
            return result(2, 2, 1);
        });

        LocalSimulationResult result = new LocalSimulatedClusterRuntime().execute(request(
                List.of(retryingTask, task("split-2", () -> result(3, 3, 2))), 2));

        assertEquals(2, executions.get());
        assertEquals(5, result.getDiffResult().getStatistics().getSourceRowCount());
        assertEquals(5, result.getDiffResult().getStatistics().getTargetRowCount());
        assertEquals(3, result.getDiffResult().getStatistics().getTotalDifferences());
        assertEquals(List.of(SplitAttemptState.FAILED, SplitAttemptState.SUCCEEDED, SplitAttemptState.SUCCEEDED),
                result.getAttempts().stream().map(SplitAttemptRecord::getState).collect(Collectors.toList()));

        JsonNode manifest = new ObjectMapper().readTree(result.getManifestPath().toFile());
        assertEquals("LOCAL", manifest.path("executionMode").asText());
        assertEquals(3, manifest.path("totalDifferences").asLong());
        assertEquals(2, manifest.path("attempts").size());
        assertTrue(manifest.path("attempts").findValuesAsText("state").stream()
                .allMatch(SplitAttemptState.SUCCEEDED.name()::equals));
        try (Stream<Path> paths = Files.list(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void shouldRejectNonLocalExecutionModeWithoutRunningTasks() {
        AtomicInteger executions = new AtomicInteger();
        LocalSimulationRequest request = LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder().executionMode(ExecutionMode.YARN).build())
                .splitTasks(List.of(task("split-1", () -> {
                    executions.incrementAndGet();
                    return result(1, 1, 0);
                })))
                .manifestDirectory(temporaryDirectory)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new LocalSimulatedClusterRuntime().execute(request));

        assertTrue(exception.getMessage().contains("LOCAL"));
        assertEquals(0, executions.get());
        assertFalse(Files.exists(temporaryDirectory.resolve("manifest.json")));
    }

    @Test
    void shouldNotPublishManifestWhenRetriesAreExhausted() {
        LocalSimulationRequest request = request(List.of(task("split-1", () -> {
            throw new IllegalStateException("permanent failure");
        })), 2);

        LocalClusterExecutionException exception = assertThrows(LocalClusterExecutionException.class,
                () -> new LocalSimulatedClusterRuntime().execute(request));

        assertTrue(exception.getMessage().contains("2 attempt"));
        assertFalse(Files.exists(temporaryDirectory.resolve("manifest.json")));
    }

    @Test
    void shouldKeepCredentialsOutOfManifest() throws Exception {
        LocalSimulationRequest request = LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.LOCAL)
                        .attributes(Map.of("password", "do-not-persist"))
                        .build())
                .splitTasks(List.of(task("split-1", () -> result(1, 1, 0))))
                .manifestDirectory(temporaryDirectory)
                .build();

        LocalSimulationResult result = new LocalSimulatedClusterRuntime().execute(request);

        assertFalse(Files.readString(result.getManifestPath()).contains("do-not-persist"));
    }

    @Test
    void shouldExecuteSplitsConcurrentlyWhenParallelismConfigured() throws Exception {
        // 三个段必须“同时在跑”才能一起放行：串行执行时第一段会等到超时并失败
        CountDownLatch allStarted = new CountDownLatch(3);
        LocalSplitTask first = task("split-1", () -> {
            allStarted.countDown();
            assertTrue(allStarted.await(5, TimeUnit.SECONDS), "split-1 did not run concurrently");
            return result(1, 1, 0);
        });
        LocalSplitTask second = task("split-2", () -> {
            allStarted.countDown();
            assertTrue(allStarted.await(5, TimeUnit.SECONDS), "split-2 did not run concurrently");
            return result(2, 2, 1);
        });
        LocalSplitTask third = task("split-3", () -> {
            allStarted.countDown();
            assertTrue(allStarted.await(5, TimeUnit.SECONDS), "split-3 did not run concurrently");
            return result(3, 3, 0);
        });

        LocalSimulationRequest request = LocalSimulationRequest.builder()
                .submissionId("submission-parallel")
                .executionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.LOCAL)
                        .parallelism(3)
                        .maxAttempts(1)
                        .build())
                .splitTasks(List.of(first, second, third))
                .manifestDirectory(temporaryDirectory)
                .build();

        LocalSimulationResult result = new LocalSimulatedClusterRuntime().execute(request);

        // 合并结果仍按输入顺序（与并行度无关）
        assertEquals(6, result.getDiffResult().getStatistics().getSourceRowCount());
        assertEquals(6, result.getDiffResult().getStatistics().getTargetRowCount());
        assertEquals(List.of("split-1", "split-2", "split-3"),
                result.getAttempts().stream().map(SplitAttemptRecord::getSplitId).collect(Collectors.toList()));
        JsonNode manifest = new ObjectMapper().readTree(result.getManifestPath().toFile());
        assertEquals(3, manifest.path("successfulSplitIds").size());
    }

    private LocalSimulationRequest request(List<LocalSplitTask> tasks, int maximumAttempts) {
        return LocalSimulationRequest.builder()
                .submissionId("submission-1")
                .executionSpec(ClusterExecutionSpec.builder()
                        .executionMode(ExecutionMode.LOCAL)
                        .maxAttempts(maximumAttempts)
                        .build())
                .splitTasks(tasks)
                .manifestDirectory(temporaryDirectory)
                .build();
    }

    private LocalSplitTask task(String splitId, ThrowingResultSupplier supplier) {
        return new LocalSplitTask() {
            @Override
            public String getSplitId() {
                return splitId;
            }

            @Override
            public DiffResult execute() throws Exception {
                return supplier.get();
            }
        };
    }

    private DiffResult result(long sourceRows, long targetRows, long mismatchCount) {
        return DiffResult.builder()
                .differences(List.of())
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRows)
                        .targetRowCount(targetRows)
                        .sourceMissingCount(0)
                        .targetMissingCount(0)
                        .mismatchCount(mismatchCount)
                        .totalDifferences(mismatchCount)
                        .processingTimeMs(1)
                        .unchangedCount(Math.min(sourceRows, targetRows) - mismatchCount)
                        .build())
                .metadata(Map.of())
                .build();
    }

    @FunctionalInterface
    private interface ThrowingResultSupplier {
        DiffResult get() throws Exception;
    }
}
