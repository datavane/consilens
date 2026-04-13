package com.consilens.core.diff;

import java.util.List;

/**
 * Consumer for streaming diff rows.
 */
public interface DiffSink extends AutoCloseable {

    void onDiffRow(DiffRow diffRow);

    default void onDiffRows(List<DiffRow> diffRows) {
        if (diffRows == null || diffRows.isEmpty()) {
            return;
        }
        for (DiffRow diffRow : diffRows) {
            onDiffRow(diffRow);
        }
    }

    default void onComplete(DiffResult.DiffStatistics statistics) {
        // no-op
    }

    @Override
    default void close() {
        // no-op
    }

    static DiffSink noop() {
        return diffRow -> {
        };
    }
}
