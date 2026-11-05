package com.consilens.sink.api;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
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

    private final List<Sink> diffRecordSinks = new ArrayList<>();
    private final List<Sink> resultSinks = new ArrayList<>();
    private final SinkRegistry registry = new SinkRegistry();
    private final ResultConfig config;
    private final java.util.concurrent.atomic.AtomicInteger failureCount =
            new java.util.concurrent.atomic.AtomicInteger();

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
            Sink sink = null;
            try {
                sink = registry.create(sinkConfig.getFormat(), sinkConfig.getType());
                sink.open(sinkConfig, context);
                if (SinkTypes.DIFF_RECORD.equalsIgnoreCase(sinkConfig.getType())) {
                    diffRecordSinks.add(sink);
                } else if (SinkTypes.RESULT.equalsIgnoreCase(sinkConfig.getType())) {
                    resultSinks.add(sink);
                }
            } catch (Exception exception) {
                // open 失败时 sink 还未加入列表，必须在这里释放它已申请的资源
                // （连接池、文件句柄等），否则 close() 永远碰不到它。
                closeAfterFailedOpen(sink);
                handleSinkFailure(exception);
            }
        }
    }

    private void closeAfterFailedOpen(Sink sink) {
        if (sink == null) {
            return;
        }
        try {
            sink.close();
        } catch (Exception e) {
            log.warn("Failed to close sink after open failure: {}", e.getMessage(), e);
        }
    }

    public void onDiffRecords(List<DiffRow> rows, DiffContext context) {
        diffRecordSinks.forEach(sink -> safeRun(() -> sink.onDiffRecords(rows, context)));
    }

    /** 容差内行只给 diff-record 类 sink：result sink 负责统计，行级输出走记录通道。 */
    public void onWithinToleranceRecords(List<DiffRow> rows, DiffContext context) {
        diffRecordSinks.forEach(sink -> safeRun(() -> sink.onWithinToleranceRecords(rows, context)));
    }

    public void onResult(DiffResult result, DiffContext context) {
        resultSinks.forEach(sink -> safeRun(() -> sink.onResult(result, context)));
    }

    public void onError(DiffContext context, Throwable error) {
        // 失败通知要同时给两类 sink：diff-record sink（如 JSON 文件）也需要在
        // 出错时收尾/关闭文件，否则只写了一半的产物会被当成完整结果。
        diffRecordSinks.forEach(sink -> safeRun(() -> sink.onError(context, error)));
        resultSinks.forEach(sink -> safeRun(() -> sink.onError(context, error)));
    }

    public void close() {
        // 单个 sink 关闭失败不能中断其余 sink 的收尾（结果文件只在 close 时生成）
        RuntimeException firstFailure = null;
        for (Sink sink : allSinks()) {
            try {
                sink.close();
            } catch (Exception e) {
                // 不能走 handleSinkFailure：failOnSinkError 时会立即抛出并中断后续 sink 的收尾
                failureCount.incrementAndGet();
                log.warn("Sink close failed: {}", e.getMessage(), e);
                if (firstFailure == null) {
                    firstFailure = e instanceof RuntimeException
                            ? (RuntimeException) e
                            : new RuntimeException("Sink close failed", e);
                }
            }
        }
        if (firstFailure != null && config != null && config.isFailOnSinkError()) {
            throw firstFailure;
        }
    }

    private List<Sink> allSinks() {
        List<Sink> all = new ArrayList<>(diffRecordSinks.size() + resultSinks.size());
        all.addAll(diffRecordSinks);
        all.addAll(resultSinks);
        return all;
    }

    /**
     * 已写入的差异记录数。多个 diff-record sink 收到的是同一批行，取最大值而不是求和，
     * 否则配两个 sink 就会把行数翻倍。
     */
    public long diffRecordCount() {
        return diffRecordSinks.stream()
                .mapToLong(Sink::writtenRecordCount)
                .filter(count -> count >= 0)
                .max()
                .orElse(0L);
    }

    /** 出现过多少次 sink 失败（failOnSinkError=false 时这些失败会被吞掉）。 */
    public int failureCount() {
        return failureCount.get();
    }

    public boolean hasFailures() {
        return failureCount.get() > 0;
    }

    public boolean diffRecordTruncated() {
        return diffRecordSinks.stream().anyMatch(Sink::isTruncated);
    }

    private void safeRun(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            handleSinkFailure(e);
        }
    }

    private void handleSinkFailure(Exception exception) {
        failureCount.incrementAndGet();
        if (config != null && config.isFailOnSinkError()) {
            throw new RuntimeException("Sink execution failed: " + exception.getMessage(), exception);
        }
        log.warn("Sink execution failed: {}", exception.getMessage(), exception);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
