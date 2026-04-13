package com.consilens.core.diff;

import java.util.List;

/**
 * Producer for diff rows.
 */
public interface DiffEmitter {

    void setDiffSink(DiffSink diffSink);

    DiffSink getDiffSink();

    default void emitDiffRow(DiffRow diffRow) {
        DiffSink sink = getDiffSink();
        if (sink != null && diffRow != null) {
            sink.onDiffRow(diffRow);
        }
    }

    default void emitDiffRows(List<DiffRow> diffRows) {
        DiffSink sink = getDiffSink();
        if (sink != null) {
            sink.onDiffRows(diffRows);
        }
    }

    default void completeDiff(DiffResult.DiffStatistics statistics) {
        DiffSink sink = getDiffSink();
        if (sink != null) {
            sink.onComplete(statistics);
        }
    }
}
