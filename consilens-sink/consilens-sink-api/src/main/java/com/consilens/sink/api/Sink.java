package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.model.SinkConfig;

import java.util.List;

/**
 * Unified Sink interface; each format + type combination maps to one Sink implementation.
 */
public interface Sink extends AutoCloseable {

    void open(SinkConfig config, DiffContext context) throws Exception;

    default void onDiffRecords(List<DiffRow> rows, DiffContext context) throws Exception {}

    default void onResult(DiffResult result, DiffContext context) throws Exception {}

    default void onSegmentComplete(SegmentResult segmentResult) throws Exception {}

    default void onError(DiffContext context, Throwable error) throws Exception {}

    @Override
    default void close() throws Exception {}
}
