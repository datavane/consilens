package com.consilens.core.segment.strategy;

import com.consilens.core.segment.TableSegment;
import com.consilens.core.segmentation.IntelligentSegmenter;
import com.consilens.core.segmentation.KeyVector;
import com.consilens.core.segmentation.SegmentStatistics;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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

        KeyVector minKey = extractKeyVector(table.getMinKey().orElse(null));
        KeyVector maxKey = extractKeyVector(table.getMaxKey().orElse(null));
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
        return CompletableFuture.supplyAsync(() -> {
            if (!supports(table)) {
                throw new IllegalStateException("Table segment is not compatible with range segmentation");
            }

            log.info("All key columns are numeric, using range-based segmentation");
            List<TableSegment> segments = intelligentSegmenter.segmentTable(table, maxSegments, executor).join();
            SegmentStatistics stats = intelligentSegmenter.getSegmentStatistics(segments);
            log.info("Range-based segmentation completed: {}", stats.getSummary());
            return segments;
        }, executor);
    }

    private KeyVector extractKeyVector(Object keyObject) {
        if (keyObject == null) {
            return null;
        }

        if (keyObject instanceof List) {
            List<?> keyList = (List<?>) keyObject;
            List<Comparable> comparableValues = new ArrayList<>();
            for (Object value : keyList) {
                if (value instanceof Comparable) {
                    comparableValues.add((Comparable<?>) value);
                } else {
                    throw new IllegalArgumentException("Key values must be comparable: " + value);
                }
            }
            return new KeyVector(comparableValues);
        } else if (keyObject instanceof Comparable) {
            return new KeyVector((Comparable<?>) keyObject);
        } else {
            throw new IllegalArgumentException("Unsupported key object type: " + keyObject.getClass());
        }
    }
}
