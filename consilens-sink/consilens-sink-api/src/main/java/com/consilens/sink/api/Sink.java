package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;

import java.util.List;

/**
 * Unified Sink interface; each format + type combination maps to one Sink implementation.
 */
public interface Sink extends AutoCloseable {

    void open(SinkConfig config, DiffContext context) throws Exception;

    default void onDiffRecords(List<DiffRow> rows, DiffContext context) throws Exception {}

    /**
     * 容差内（未计入差异）的行。默认实现忽略：只有需要在报告里单列的实现
     * （console / csv）才覆写，避免把"不是差异"的行混进 diff-record 流。
     */
    default void onWithinToleranceRecords(List<DiffRow> rows, DiffContext context) throws Exception {}

    default void onResult(DiffResult result, DiffContext context) throws Exception {}

    default void onError(DiffContext context, Throwable error) throws Exception {}

    /** Number of diff records actually written (used by file sinks for pagination totals). */
    default long writtenRecordCount() {
        return -1;
    }

    /** Whether the sink stopped writing because it reached its row limit. */
    default boolean isTruncated() {
        return false;
    }

    @Override
    default void close() throws Exception {}
}
