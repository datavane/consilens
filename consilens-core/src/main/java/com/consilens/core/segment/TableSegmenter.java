package com.consilens.core.segment;

import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segmentation.CheckpointSelector;
import com.consilens.core.segmentation.IntelligentSegmenter;
import com.consilens.core.segment.strategy.FallbackSegmentStrategy;
import com.consilens.core.segment.strategy.RangeSegmentStrategy;
import com.consilens.core.segment.strategy.RowSampleSegmentStrategy;
import com.consilens.core.segment.strategy.SegmentStrategy;
import com.consilens.core.segment.strategy.SegmentStrategyType;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.thread.ExecutorProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Enhanced table segmentation system with pluggable strategies.
 */
@Slf4j
public class TableSegmenter {

    private final SegmenterConfig config;
    private final IntelligentSegmenter intelligentSegmenter;
    private final SegmentStrategy rangeStrategy;
    private final SegmentStrategy rowSampleStrategy;
    private final SegmentStrategy fallbackStrategy;
    private final ExecutorProvider executorProvider;

    public TableSegmenter(DatabaseAdapter database, SegmenterConfig config) {
        this(database, config, null);
    }

    public TableSegmenter(DatabaseAdapter database, SegmenterConfig config, ExecutorProvider executorProvider) {
        this.config = config;
        this.intelligentSegmenter = new IntelligentSegmenter(
                database,
                buildCheckpointSelector(config),
                config.getSampleSize(),
                true);
        this.rangeStrategy = new RangeSegmentStrategy(intelligentSegmenter);
        this.rowSampleStrategy = new RowSampleSegmentStrategy();
        this.fallbackStrategy = new FallbackSegmentStrategy(config);
        this.executorProvider = executorProvider != null ? executorProvider
                : new ExecutorProvider(ConcurrencyConfig.defaultConfig());
    }

    /**
     * Create optimal segments for table comparison using configured strategy.
     */
    public CompletableFuture<List<TableSegment>> createOptimalSegments(
            TableSegment table, int targetFactor, long threshold) {
        int segmentCount = determineSegmentCount(table, targetFactor, threshold);
        return segment(table, segmentCount);
    }

    /**
     * Create segments using the configured strategy.
     */
    public CompletableFuture<List<TableSegment>> createIntelligentSegments(
            TableSegment table, int maxSegments) {
        return segment(table, maxSegments);
    }

    private CompletableFuture<List<TableSegment>> segment(TableSegment table, int maxSegments) {
        SegmentStrategy strategy = selectStrategy(table);
        return strategy.segment(table, maxSegments, getIoExecutor())
                .exceptionally(e -> {
                    log.error("Segmentation strategy {} failed, falling back to basic segmentation",
                            strategy.getType(), e);
                    return fallbackStrategy.segment(table, maxSegments, getIoExecutor()).join();
                });
    }

    private int determineSegmentCount(TableSegment table, int targetFactor, long threshold) {
        if (targetFactor <= 2) {
            return 2;
        }

        long estimatedSize = table.approximateSize();
        if (estimatedSize < 0) {
            try {
                estimatedSize = table.count();
            } catch (Exception e) {
                log.warn("Failed to estimate table size for optimal segmentation, using target factor {}", targetFactor, e);
                return targetFactor;
            }
        }

        if (threshold > 0 && estimatedSize <= threshold * (long) targetFactor) {
            return 2;
        }

        return targetFactor;
    }

    private Executor getIoExecutor() {
        return executorProvider.getIoExecutor();
    }

    private CheckpointSelector buildCheckpointSelector(SegmenterConfig config) {
        int maxSegments = Math.max(2, config.getMaxSegmentCount());
        long rawThreshold = config.getBisectionThreshold();
        int threshold = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, rawThreshold));
        return new CheckpointSelector(maxSegments, threshold);
    }

    private SegmentStrategy selectStrategy(TableSegment table) {
        SegmentStrategyType strategyType = config.getStrategyType();

        if (strategyType == SegmentStrategyType.AUTO) {
            if (rangeStrategy.supports(table)) {
                log.info("Segmenter strategy auto-selected: RANGE");
                return rangeStrategy;
            }
            log.info("Segmenter strategy auto-selected: ROW_SAMPLE");
            return rowSampleStrategy;
        }

        if (strategyType == SegmentStrategyType.RANGE && rangeStrategy.supports(table)) {
            return rangeStrategy;
        }
        if (strategyType == SegmentStrategyType.ROW_SAMPLE) {
            return rowSampleStrategy;
        }
        if (strategyType == SegmentStrategyType.FALLBACK) {
            return fallbackStrategy;
        }

        log.warn("Segmenter strategy {} is not supported for table {}, falling back", strategyType,
                table != null ? table.getTablePath() : "null");
        return fallbackStrategy;
    }

    /**
     * Configuration for table segmenter.
     */
    @Getter
    public static class SegmenterConfig {
        private final long bisectionThreshold;
        private final int sampleSize;
        private final int maxSegmentCount;
        private final SegmentStrategyType strategyType;

        public SegmenterConfig(long bisectionThreshold, int sampleSize, int maxSegmentCount) {
            this(bisectionThreshold, sampleSize, maxSegmentCount, SegmentStrategyType.AUTO);
        }

        public SegmenterConfig(long bisectionThreshold, int sampleSize, int maxSegmentCount,
                SegmentStrategyType strategyType) {
            this.bisectionThreshold = bisectionThreshold;
            this.sampleSize = sampleSize;
            this.maxSegmentCount = maxSegmentCount;
            this.strategyType = strategyType != null ? strategyType : SegmentStrategyType.AUTO;
        }

        public static SegmenterConfig defaultConfig() {
            return new SegmenterConfig(16384, 1000, 100, SegmentStrategyType.AUTO);
        }

        public static SegmenterConfig highPerformanceConfig() {
            return new SegmenterConfig(8192, 2000, 200, SegmentStrategyType.AUTO);
        }

        public static SegmenterConfig memoryEfficientConfig() {
            return new SegmenterConfig(32768, 500, 50, SegmentStrategyType.AUTO);
        }
    }
}
