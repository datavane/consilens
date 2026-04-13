package com.consilens.sink.api;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffSink;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming bridge from DiffSink to DiffLifecycle.
 *
 * <p>Set this object on the differ in DiffService. Each diff row triggers
 * {@link #onDiffRow(DiffRow)}, which buffers rows and flushes batches to
 * {@link DiffLifecycle#onDifferencesFound(List, DiffContext)} for true streaming writes.
 *
 * <p>Processing flow:
 * <pre>
 * differ.onDiffRow(row)
 *   → LifecycleDiffSink.onDiffRow(row)
 *       → buffer.add(row)
 *       → if buffer >= batchSize: flush()
 *           → lifecycle.onDifferencesFound(batch, context)
 *               → SinkManager.onDiffRecords(batch)
 *                   → CsvDiffRecordSink.onDiffRecords(batch)  // write file immediately
 *                   → TableDiffRecordSink.onDiffRecords(batch) // insert to DB immediately
 * </pre>
 */
@Slf4j
public class LifecycleDiffSink implements DiffSink {

    /** Batch size threshold that triggers a flush. */
    private static final int DEFAULT_BATCH_SIZE = 200;

    private final DiffLifecycle lifecycle;
    private final DiffContext context;
    private final int batchSize;
    private final List<DiffRow> buffer;

    public LifecycleDiffSink(DiffLifecycle lifecycle, DiffContext context) {
        this(lifecycle, context, DEFAULT_BATCH_SIZE);
    }

    public LifecycleDiffSink(DiffLifecycle lifecycle, DiffContext context, int batchSize) {
        this.lifecycle = lifecycle;
        this.context = context;
        this.batchSize = batchSize;
        this.buffer = new ArrayList<>(batchSize);
    }

    @Override
    public void onDiffRow(DiffRow row) {
        if (row == null) return;
        buffer.add(row);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /**
     * When the differ calls back with a batch, forward directly without single-row buffering.
     */
    @Override
    public void onDiffRows(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        // Flush current buffer first to maintain order.
        if (!buffer.isEmpty()) {
            flush();
        }
        forwardToLifecycle(rows);
    }

    /**
     * Called by DiffService after diff completes to flush any remaining buffered rows.
     */
    @Override
    public void close() {
        flush();
    }

    private void flush() {
        if (buffer.isEmpty()) return;
        List<DiffRow> batch = new ArrayList<>(buffer);
        buffer.clear();
        forwardToLifecycle(batch);
    }

    private void forwardToLifecycle(List<DiffRow> batch) {
        try {
            lifecycle.onDifferencesFound(batch, context);
        } catch (Exception e) {
            log.error("LifecycleDiffSink: lifecycle.onDifferencesFound failed, batch size={}", batch.size(), e);
            throw new RuntimeException("Lifecycle sink forward failed", e);
        }
    }
}
