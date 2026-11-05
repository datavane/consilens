package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.sink.api.model.ResultConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * DiffLifecycle implementation that routes lifecycle events to SinkManager.
 * Placed in consilens-sink-api to avoid circular dependency with consilens-core.
 */
@Slf4j
public class DefaultDiffLifecycle implements DiffLifecycle {

    private final SinkManager sinkManager;

    public DefaultDiffLifecycle(ResultConfig resultConfig) {
        this.sinkManager = new SinkManager(resultConfig);
    }

    @Override
    public void onDiffStart(DiffContext context) throws Exception {
        sinkManager.open(context);
    }

    @Override
    public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) throws Exception {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        sinkManager.onDiffRecords(diffs, context);
    }

    @Override
    public void onWithinToleranceFound(List<DiffRow> rows, DiffContext context) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        sinkManager.onWithinToleranceRecords(rows, context);
    }

    @Override
    public void onDiffComplete(DiffResult result, DiffContext context) throws Exception {
        sinkManager.onResult(result, context);
    }

    @Override
    public void onDiffError(DiffContext context, Throwable error) throws Exception {
        sinkManager.onError(context, error);
    }

    @Override
    public void close() throws Exception {
        sinkManager.close();
    }

    /** 差异记录实际写入行数（sink 聚合）。 */
    public long writtenRecordCount() {
        return sinkManager.diffRecordCount();
    }

    /** 差异写入是否达到行数上限被截断。 */
    public boolean isTruncated() {
        return sinkManager.diffRecordTruncated();
    }

    /** sink 部分失败（failOnSinkError=false 时被吞掉，但结果必须标注）。 */
    public int sinkFailureCount() {
        return sinkManager.failureCount();
    }

    public boolean hasSinkFailures() {
        return sinkManager.hasFailures();
    }
}
