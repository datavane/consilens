package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the lifecycle of all Sink instances and routes events by type.
 */
@Slf4j
public class SinkManager {

    private final List<SinkHolder> diffRecordSinks = new ArrayList<>();
    private final List<SinkHolder> resultSinks = new ArrayList<>();
    private final SinkRegistry registry = new SinkRegistry();
    private final ResultConfig config;

    public SinkManager(ResultConfig config) {
        this.config = config;
    }

    public void open(DiffContext context) throws Exception {
        if (config == null || config.getSinks() == null) {
            return;
        }
        for (SinkConfig sinkConfig : config.getSinks()) {
            if (!sinkConfig.isEnabled()) {
                continue;
            }
            Sink sink = registry.create(sinkConfig.getFormat(), sinkConfig.getType());
            sink.open(sinkConfig, context);
            SinkHolder holder = new SinkHolder(sinkConfig, sink);
            if ("diff-record".equalsIgnoreCase(sinkConfig.getType())) {
                diffRecordSinks.add(holder);
            } else if ("result".equalsIgnoreCase(sinkConfig.getType())) {
                resultSinks.add(holder);
            }
        }
    }

    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        diffRecordSinks.forEach(holder -> safeRun(() -> holder.sink().onDiffRecords(rows, context)));
    }

    public void onResult(DiffResult result, DiffContext context) {
        resultSinks.forEach(holder -> safeRun(() -> holder.sink().onResult(result, context)));
    }

    public void onSegmentComplete(SegmentResult segmentResult) {
        diffRecordSinks.forEach(holder -> safeRun(() -> holder.sink().onSegmentComplete(segmentResult)));
        resultSinks.forEach(holder -> safeRun(() -> holder.sink().onSegmentComplete(segmentResult)));
    }

    public void onError(DiffContext context, Throwable error) {
        resultSinks.forEach(holder -> safeRun(() -> holder.sink().onError(context, error)));
    }

    public void close() {
        for (SinkHolder holder : diffRecordSinks) {
            safeRun(() -> holder.sink().close());
        }
        for (SinkHolder holder : resultSinks) {
            safeRun(() -> holder.sink().close());
        }
    }

    private void safeRun(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            if (config != null && config.isFailOnSinkError()) {
                throw new RuntimeException("Sink execution failed", e);
            }
            log.warn("Sink execution failed: {}", e.getMessage(), e);
        }
    }

    private static class SinkHolder {
        private final SinkConfig config;
        private final Sink sink;

        SinkHolder(SinkConfig config, Sink sink) {
            this.config = config;
            this.sink = sink;
        }

        Sink sink() {
            return sink;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
