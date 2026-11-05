package com.consilens.core.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory diff sink for backward compatibility.
 *
 * <p>上限在**入队时**按当前条数原子判断：调用方（ChecksumDiffer/JoinDiffer）是"先检查条数、
 * 再逐个 onDiffRow"，多个分段并发跑时纯检查会一起通过，实际条数可以明显超过 maxDifferences。
 */
public class InMemoryDiffSink implements DiffSink {

    private final List<DiffRow> differences = Collections.synchronizedList(new ArrayList<>());
    private volatile long maxDifferences = Long.MAX_VALUE;

    /** @param maxDifferences 上限；0 或负数表示不限 */
    public void setMaxDifferences(long maxDifferences) {
        this.maxDifferences = maxDifferences > 0 ? maxDifferences : Long.MAX_VALUE;
    }

    public long getMaxDifferences() {
        return maxDifferences;
    }

    @Override
    public void onDiffRow(DiffRow diffRow) {
        if (diffRow == null) {
            return;
        }
        synchronized (differences) {
            long limit = maxDifferences;
            if (differences.size() >= limit) {
                throw new IllegalStateException("Diff result exceeds maxDifferences=" + limit
                        + ". Increase strategy.maxDifferences or narrow the comparison scope.");
            }
            differences.add(diffRow);
        }
    }

    public List<DiffRow> getDifferences() {
        synchronized (differences) {
            return new ArrayList<>(differences);
        }
    }

    public int size() {
        return differences.size();
    }
}
