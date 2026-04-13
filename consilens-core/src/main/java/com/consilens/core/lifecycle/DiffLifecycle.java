package com.consilens.core.lifecycle;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;

import java.util.List;

/**
 * Internal lifecycle interface for core; not exposed as an SPI extension point.
 * The only sink-aware implementation is DefaultDiffLifecycle (in consilens-sink-api).
 * Use NoopDiffLifecycle when no sink is configured.
 */
public interface DiffLifecycle {

    void onDiffStart(DiffContext context) throws Exception;

    void onSegmentComplete(SegmentResult result) throws Exception;

    void onDifferencesFound(List<DiffRow> diffs, DiffContext context) throws Exception;

    void onDiffComplete(DiffResult result, DiffContext context) throws Exception;

    void onDiffError(DiffContext context, Throwable error) throws Exception;

    default void close() throws Exception {
        // no-op
    }
}
