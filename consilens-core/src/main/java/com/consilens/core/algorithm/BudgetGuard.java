package com.consilens.core.algorithm;

import com.consilens.core.exception.BudgetExceededException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforces maxDurationMs / maxRowsScanned / maxBytesFetched budgets at segment
 * and row checkpoints. All limits are inclusive; {@link Long#MAX_VALUE} means
 * unlimited. Thread-safe: differ paths invoke it from IO and CPU pools.
 */
public final class BudgetGuard {

    private final long maxDurationMs;
    private final long maxRowsScanned;
    private final long maxBytesFetched;
    private final long startNanos;
    private final AtomicLong rowsScanned = new AtomicLong();
    private final AtomicLong bytesFetched = new AtomicLong();

    public BudgetGuard(long maxDurationMs, long maxRowsScanned, long maxBytesFetched) {
        this.maxDurationMs = maxDurationMs > 0 ? maxDurationMs : Long.MAX_VALUE;
        this.maxRowsScanned = maxRowsScanned > 0 ? maxRowsScanned : Long.MAX_VALUE;
        this.maxBytesFetched = maxBytesFetched > 0 ? maxBytesFetched : Long.MAX_VALUE;
        this.startNanos = System.nanoTime();
    }

    public static BudgetGuard unlimited() {
        return new BudgetGuard(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public void addRowsScanned(long delta) {
        if (delta <= 0) {
            // delta 为 0 也要检查墙钟预算：join 侧的行数来自统计 SQL，统计拿不到行数时
            // 这里会一直是 0，若提前 return 连 maxDurationMs 都不会检查（两个预算一起失效）。
            checkDuration();
            return;
        }
        long total = rowsScanned.addAndGet(delta);
        if (total > maxRowsScanned) {
            throw new BudgetExceededException("maxRowsScanned", maxRowsScanned, total);
        }
        checkDuration();
    }

    public void addBytesFetched(long delta) {
        if (delta <= 0) {
            return;
        }
        long total = bytesFetched.addAndGet(delta);
        if (total > maxBytesFetched) {
            throw new BudgetExceededException("maxBytesFetched", maxBytesFetched, total);
        }
        checkDuration();
    }

    public void checkDuration() {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > maxDurationMs) {
            throw new BudgetExceededException("maxDurationMs", maxDurationMs, elapsedMs);
        }
    }
}
