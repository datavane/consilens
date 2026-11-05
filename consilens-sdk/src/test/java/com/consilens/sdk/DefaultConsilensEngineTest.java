package com.consilens.sdk;

import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConsilensEngineTest {

    @Test
    void shouldDelegateToRuntimeAndAttachColumnStats() {
        DiffResult result = resultWithOneMismatch();
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> result);

        DiffResult actual = engine.execute(request());

        assertThat(actual.getDifferences()).isSameAs(result.getDifferences());
        assertThat(actual.getColumnStats()).hasSize(1);
        assertThat(actual.getColumnStats().get(0).getName()).isEqualTo("amount");
        assertThat(actual.getColumnStats().get(0).getUpdateCount()).isEqualTo(1);
        assertThat(actual.getFingerprint()).isNotNull();
        assertThat(actual.getFingerprint().getRunFingerprint()).startsWith("sha256:");
        assertThat(actual.getEvidence()).isNotNull();
    }

    @Test
    void shouldProduceStableFingerprintForSameRequest() {
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> resultWithOneMismatch());

        DiffResult first = engine.execute(request());
        DiffResult second = engine.execute(request());

        assertThat(first.getFingerprint().getRunFingerprint())
                .isEqualTo(second.getFingerprint().getRunFingerprint());
    }

    @Test
    void shouldApplyRunOptionMaxDifferences() {
        AtomicReference<CompareRequest> captured = new AtomicReference<>();
        CompareRuntime runtime = request -> {
            captured.set(request);
            return resultWithOneMismatch();
        };
        DefaultConsilensEngine engine = new DefaultConsilensEngine(runtime);

        engine.execute(request(), RunOptions.maxDifferences(42));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getExecutionOptions()).isNotNull();
        assertThat(captured.get().getExecutionOptions().getMaxDifferences()).isEqualTo(42);
    }

    @Test
    void shouldApplyRunOptionBudgets() {
        AtomicReference<CompareRequest> captured = new AtomicReference<>();
        CompareRuntime runtime = request -> {
            captured.set(request);
            return resultWithOneMismatch();
        };
        DefaultConsilensEngine engine = new DefaultConsilensEngine(runtime);

        engine.execute(request(), RunOptions.builder()
                .maxDifferences(10)
                .maxDurationMs(2_000)
                .maxRowsScanned(5_000)
                .maxBytesFetched(1_000_000)
                .build());

        assertThat(captured.get().getExecutionOptions().getMaxDifferences()).isEqualTo(10);
        assertThat(captured.get().getExecutionOptions().getMaxDurationMs()).isEqualTo(2_000);
        assertThat(captured.get().getExecutionOptions().getMaxRowsScanned()).isEqualTo(5_000);
        assertThat(captured.get().getExecutionOptions().getMaxBytesFetched()).isEqualTo(1_000_000);
    }

    @Test
    void shouldRejectNonPositiveBudget() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RunOptions.builder().maxDurationMs(0))).hasMessageContaining("maxDurationMs");
    }

    @Test
    void shouldNotifyRunListenerOnSuccess() {
        AtomicReference<DiffResult> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> resultWithOneMismatch());

        engine.execute(request(), RunOptions.builder().listener(new RunListener() {
            @Override
            public void onStart(CompareRequest request) {
                starts.incrementAndGet();
            }

            @Override
            public void onComplete(CompareRequest request, DiffResult result, long durationMs) {
                completed.set(result);
            }

            @Override
            public void onError(CompareRequest request, Throwable error, long durationMs) {
                failed.set(error);
            }
        }).build());

        assertThat(starts.get()).isEqualTo(1);
        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().getColumnStats()).hasSize(1);
        assertThat(failed.get()).isNull();
    }

    @Test
    void shouldNotifyRunListenerOnError() {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> {
            throw new IllegalStateException("boom");
        });

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(DiffSdkException.class,
                () -> engine.execute(request(), RunOptions.builder().listener(new RunListener() {
                    @Override
                    public void onError(CompareRequest request, Throwable error, long durationMs) {
                        failed.set(error);
                    }
                }).build()))).hasMessageContaining("boom");
        assertThat(failed.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldIgnoreListenerFailures() {
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> resultWithOneMismatch());

        DiffResult result = engine.execute(request(), RunOptions.builder()
                .listener(new RunListener() {
                    @Override
                    public void onStart(CompareRequest request) {
                        throw new IllegalStateException("telemetry down");
                    }

                    @Override
                    public void onComplete(CompareRequest request, DiffResult value, long durationMs) {
                        throw new IllegalStateException("telemetry down");
                    }
                }).build());

        assertThat(result).isNotNull();
        assertThat(result.getColumnStats()).hasSize(1);
    }

    @Test
    void shouldWireProgressListenerIntoExecutionOptions() {
        AtomicReference<CompareRequest> captured = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.consilens.connector.api.planner.CompareProgress>
                progress = new java.util.concurrent.atomic.AtomicReference<>();
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> {
            captured.set(request);
            return resultWithOneMismatch();
        });

        engine.execute(request(), RunOptions.builder().listener(new RunListener() {
            @Override
            public void onProgress(com.consilens.connector.api.planner.CompareProgress value) {
                progress.set(value);
            }
        }).build());

        assertThat(captured.get().getExecutionOptions().getProgressListener()).isNotNull();
        captured.get().getExecutionOptions().getProgressListener().onProgress(
                com.consilens.connector.api.planner.CompareProgress.builder()
                        .sourceRows(2)
                        .targetRows(2)
                        .differences(1)
                        .elapsedMs(5)
                        .build());
        assertThat(progress.get().getSourceRows()).isEqualTo(2);
        assertThat(progress.get().getDifferences()).isEqualTo(1);
    }

    @Test
    void shouldExposeEngineInfo() {
        DefaultConsilensEngine engine = new DefaultConsilensEngine(request -> DiffResult.empty(null, null));

        assertThat(engine.info().getName()).isEqualTo("consilens");
        assertThat(engine.info().getVersion()).isNotBlank();
        assertThat(engine.info().getApiVersion()).isEqualTo(EngineInfo.API_VERSION);
    }

    private static CompareRequest request() {
        return CompareRequest.builder()
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("amount")).build())
                .build();
    }

    private static DiffResult resultWithOneMismatch() {
        DiffRow row = DiffRow.modified(
                List.of(1),
                List.of("10.0"), List.of("11.0"),
                List.of("amount"), List.of("amount"),
                List.of("amount"), List.of("amount"));
        return DiffResult.builder()
                .differences(List.of(row))
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(1)
                        .targetRowCount(1)
                        .mismatchCount(1)
                        .totalDifferences(1)
                        .build())
                .build();
    }
}
