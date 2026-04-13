package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.SegmentResult;
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
    public void onSegmentComplete(SegmentResult result) throws Exception {
        sinkManager.onSegmentComplete(result);
    }

    @Override
    public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) throws Exception {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        sinkManager.onDiffRecords(diffs, context);
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
}
