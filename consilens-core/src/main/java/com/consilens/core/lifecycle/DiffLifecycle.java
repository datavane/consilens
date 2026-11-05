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

    void onDifferencesFound(List<DiffRow> diffs, DiffContext context) throws Exception;

    /**
     * 容差内（未计入差异）的行。默认口径下这些行不属于"差异"，因此单独走一条事件：
     * 混进 {@link #onDifferencesFound} 会让 sink 的行数上限、截断标记和计数全部失真。
     * 不关心这一事件的实现保持默认空实现即可。
     */
    default void onWithinToleranceFound(List<DiffRow> rows, DiffContext context) throws Exception {
        // no-op
    }

    void onDiffComplete(DiffResult result, DiffContext context) throws Exception;

    void onDiffError(DiffContext context, Throwable error) throws Exception;

    default void close() throws Exception {
        // no-op
    }
}
