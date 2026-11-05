package com.consilens.core.segment.strategy;

import com.consilens.core.segment.TableSegment;
import com.consilens.core.segmentation.IntelligentSegmenter;
import com.consilens.core.segmentation.KeyVector;
import com.consilens.core.segmentation.SegmentStatistics;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
public class RangeSegmentStrategy implements SegmentStrategy {

    private final IntelligentSegmenter intelligentSegmenter;

    public RangeSegmentStrategy(IntelligentSegmenter intelligentSegmenter) {
        this.intelligentSegmenter = intelligentSegmenter;
    }

    @Override
    public SegmentStrategyType getType() {
        return SegmentStrategyType.RANGE;
    }

    @Override
    public boolean supports(TableSegment table) {
        if (table == null || !table.isBounded()) {
            return false;
        }

        KeyVector minKey = KeyVector.fromKeyObject(table.getMinKey().orElse(null));
        KeyVector maxKey = KeyVector.fromKeyObject(table.getMaxKey().orElse(null));
        if (minKey == null || maxKey == null) {
            return false;
        }

        for (int i = 0; i < minKey.getDimensions(); i++) {
            Comparable minVal = minKey.getValue(i);
            Comparable maxVal = maxKey.getValue(i);
            if (!(minVal instanceof Number && maxVal instanceof Number)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public CompletableFuture<List<TableSegment>> segment(TableSegment table, int maxSegments, Executor executor) {
        if (!supports(table)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Table segment is not compatible with range segmentation"));
        }

        log.info("All key columns are numeric, using range-based segmentation");
        return intelligentSegmenter.segmentTable(table, maxSegments, executor)
                .thenApply(segments -> {
                    SegmentStatistics stats = intelligentSegmenter.getSegmentStatistics(segments);
                    log.info("Range-based segmentation completed: {}", stats.getSummary());
                    return segments;
                });
    }
}
