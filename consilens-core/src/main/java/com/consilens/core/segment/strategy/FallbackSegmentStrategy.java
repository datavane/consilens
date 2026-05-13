package com.consilens.core.segment.strategy;

import com.consilens.core.segment.TableSegment;
import com.consilens.core.segment.TableSegmenter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
public class FallbackSegmentStrategy implements SegmentStrategy {

    private final TableSegmenter.SegmenterConfig config;

    public FallbackSegmentStrategy(TableSegmenter.SegmenterConfig config) {
        this.config = config;
    }

    @Override
    public SegmentStrategyType getType() {
        return SegmentStrategyType.FALLBACK;
    }

    @Override
    public boolean supports(TableSegment table) {
        return table != null;
    }

    @Override
    public CompletableFuture<List<TableSegment>> segment(TableSegment table, int maxSegments, Executor executor) {
        return CompletableFuture.supplyAsync(() -> fallbackToBasicSegmentation(table, maxSegments), executor);
    }

    private List<TableSegment> fallbackToBasicSegmentation(TableSegment table, int maxSegments) {
        try {
            log.debug("Using fallback basic segmentation for table: {}", table.getTablePath());

            TableSegment.ChecksumResult stats = table.countAndChecksum();
            long totalRows = stats.getCount();

            if (totalRows == 0) {
                return List.of(table);
            }

            if (totalRows <= config.getBisectionThreshold() || maxSegments <= 1) {
                log.debug("Table too small for segmentation, using single segment");
                return List.of(table);
            }

            try {
                if (table.isBounded()) {
                    List<List<Object>> checkpoints = table.chooseCheckpoints(Math.max(0, maxSegments - 1));
                    return table.segmentByCheckpoints(checkpoints);
                }
                return List.of(table);
            } catch (Exception e) {
                log.warn("Failed to segment table, using single segment", e);
                return List.of(table);
            }

        } catch (Exception e) {
            log.error("Basic segmentation also failed", e);
            return List.of(table);
        }
    }
}
