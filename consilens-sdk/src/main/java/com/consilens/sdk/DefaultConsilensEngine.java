package com.consilens.sdk;

import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.ColumnStats;
import com.consilens.core.diff.DiffEvidenceFactory;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.RunFingerprintFactory;
import com.consilens.sdk.result.ColumnStatsCalculator;
import com.consilens.sdk.verify.VerifyEvaluator;
import com.consilens.sdk.verify.VerifyPolicy;
import com.consilens.sdk.verify.VerifyResult;

import java.util.List;

/**
 * Default SDK facade. Delegates to the existing {@link CompareRuntime} and
 * post-processes the result (column stats) without changing the engine.
 */
public final class DefaultConsilensEngine implements ConsilensEngine {

    private static final long DEFAULT_MAX_DIFFERENCES = 1_000_000L;

    private final CompareRuntime runtime;

    public DefaultConsilensEngine() {
        this(new DefaultCompareRuntime());
    }

    public DefaultConsilensEngine(CompareRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
    }

    @Override
    public EngineInfo info() {
        return EngineInfo.current();
    }

    @Override
    public DiffResult execute(CompareRequest request, RunOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RunOptions effectiveOptions = options == null ? RunOptions.defaults() : options;
        long maxDifferences = resolveMaxDifferences(request, effectiveOptions);
        CompareRequest effectiveRequest = hasOverrides(effectiveOptions)
                ? withOverrides(request, effectiveOptions, maxDifferences)
                : request;
        RunListener listener = effectiveOptions.getListener();
        long startedAt = System.currentTimeMillis();
        notifyStart(listener, effectiveRequest);

        try {
            DiffResult result = runtime.execute(effectiveRequest);
            if (result == null) {
                notifyComplete(listener, effectiveRequest, null, elapsed(startedAt));
                return null;
            }
            List<ColumnStats> columnStats = ColumnStatsCalculator.calculate(
                    result, effectiveRequest, maxDifferences);
            DiffResult enriched = result.toBuilder()
                    .columnStats(columnStats)
                    .fingerprint(RunFingerprintFactory.create(effectiveRequest, info().getVersion()))
                    .evidence(DiffEvidenceFactory.from(result))
                    .build();
            notifyComplete(listener, effectiveRequest, enriched, elapsed(startedAt));
            return enriched;
        } catch (Exception exception) {
            notifyError(listener, effectiveRequest, exception, elapsed(startedAt));
            throw new DiffSdkException("Diff execution failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public VerifyResult verify(CompareRequest request, VerifyPolicy policy, RunOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        DiffResult result = execute(request, options);
        return VerifyEvaluator.evaluate(result, policy);
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private void notifyStart(RunListener listener, CompareRequest request) {
        if (listener == null) {
            return;
        }
        try {
            listener.onStart(request);
        } catch (RuntimeException ignored) {
            // telemetry must not fail the comparison
        }
    }

    private void notifyComplete(RunListener listener, CompareRequest request,
                                DiffResult result, long durationMs) {
        if (listener == null) {
            return;
        }
        try {
            listener.onComplete(request, result, durationMs);
        } catch (RuntimeException ignored) {
            // telemetry must not fail the comparison
        }
    }

    private void notifyError(RunListener listener, CompareRequest request,
                             Throwable error, long durationMs) {
        if (listener == null) {
            return;
        }
        try {
            listener.onError(request, error, durationMs);
        } catch (RuntimeException ignored) {
            // telemetry must not fail the comparison
        }
    }

    private void notifyProgress(RunListener listener,
                                com.consilens.connector.api.planner.CompareProgress progress) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // telemetry must not fail the comparison
        }
    }

    private long resolveMaxDifferences(CompareRequest request, RunOptions options) {
        if (options.getMaxDifferences() != null) {
            return options.getMaxDifferences();
        }
        CompareExecutionOptions executionOptions = request.getExecutionOptions();
        if (executionOptions != null && executionOptions.getMaxDifferences() != null) {
            return Math.max(1L, executionOptions.getMaxDifferences());
        }
        return DEFAULT_MAX_DIFFERENCES;
    }

    private boolean hasOverrides(RunOptions options) {
        return options.getMaxDifferences() != null
                || options.getMaxDurationMs() != null
                || options.getMaxRowsScanned() != null
                || options.getMaxBytesFetched() != null
                || options.getListener() != null;
    }

    private CompareRequest withOverrides(CompareRequest request, RunOptions options,
                                         long maxDifferences) {
        CompareExecutionOptions old = request.getExecutionOptions();
        CompareExecutionOptions executionOptions = CompareExecutionOptions.builder()
                .bisectionFactor(old != null ? old.getBisectionFactor() : null)
                .bisectionThreshold(old != null ? old.getBisectionThreshold() : null)
                .checksumAlgorithm(old != null ? old.getChecksumAlgorithm() : null)
                .localCompareMode(old != null ? old.getLocalCompareMode() : null)
                .validateUniqueKeys(old != null ? old.getValidateUniqueKeys() : null)
                .allowNullableKeys(old != null ? old.getAllowNullableKeys() : null)
                .maxDifferences(maxDifferences)
                .maxDurationMs(options.getMaxDurationMs() != null ? options.getMaxDurationMs()
                        : old != null ? old.getMaxDurationMs() : null)
                .maxRowsScanned(options.getMaxRowsScanned() != null ? options.getMaxRowsScanned()
                        : old != null ? old.getMaxRowsScanned() : null)
                .maxBytesFetched(options.getMaxBytesFetched() != null ? options.getMaxBytesFetched()
                        : old != null ? old.getMaxBytesFetched() : null)
                .progressListener(options.getListener() != null
                        ? progress -> notifyProgress(options.getListener(), progress)
                        : old != null ? old.getProgressListener() : null)
                .attributes(old != null ? old.getAttributes() : null)
                .build();
        return CompareRequest.builder()
                .source(request.getSource())
                .target(request.getTarget())
                .sourceKeySpec(request.getSourceKeySpec())
                .targetKeySpec(request.getTargetKeySpec())
                .sourceComparisons(request.getSourceComparisons())
                .targetComparisons(request.getTargetComparisons())
                .sourceFilter(request.getSourceFilter())
                .targetFilter(request.getTargetFilter())
                .normalizationSpec(request.getNormalizationSpec())
                .strategyPreference(request.getStrategyPreference())
                .executionOptions(executionOptions)
                .clusterExecutionSpec(request.getClusterExecutionSpec())
                .build();
    }
}
