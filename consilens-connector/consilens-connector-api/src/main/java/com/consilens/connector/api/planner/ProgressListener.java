package com.consilens.connector.api.planner;

/**
 * Optional progress callback for long-running comparisons.
 *
 * <p>Callbacks are advisory: implementations should be cheap and must not throw;
 * the engine guards against listener failures. Not every execution plan emits
 * progress; the streaming merge plan emits periodic snapshots and a final one.
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(CompareProgress progress);
}
