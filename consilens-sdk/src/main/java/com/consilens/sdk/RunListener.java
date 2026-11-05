package com.consilens.sdk;

import com.consilens.connector.api.planner.CompareProgress;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;

/**
 * Coarse-grained lifecycle callbacks for embedded runs.
 *
 * <p>Callbacks run on the caller's thread. Listener exceptions are ignored so a
 * telemetry bug cannot fail the comparison. {@link #onProgress} is emitted by
 * the streaming merge plan periodically and once at completion; other plans may
 * not emit progress.
 */
public interface RunListener {

    default void onStart(CompareRequest request) {
    }

    default void onProgress(CompareProgress progress) {
    }

    default void onComplete(CompareRequest request, DiffResult result, long durationMs) {
    }

    default void onError(CompareRequest request, Throwable error, long durationMs) {
    }
}
