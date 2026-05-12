package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.diff.DiffEmitter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffSink;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.diff.InMemoryDiffSink;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.thread.ExecutorProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/**
 * Provides the foundation for different diff algorithms.
 */
@Slf4j
public abstract class TableDiffer implements DiffEmitter {

    protected final ExecutorProvider executorProvider;
    protected final DifferConfig config;
    protected final boolean ownsExecutorProvider;
    private DiffSink diffSink;

    protected TableDiffer(DifferConfig config) {
        this(config, new ExecutorProvider(config.getConcurrencyConfig()), true);
    }

    protected TableDiffer(DifferConfig config, ExecutorProvider executorProvider) {
        this(config, executorProvider, false);
    }

    protected TableDiffer(DifferConfig config, ExecutorProvider executorProvider, boolean ownsExecutorProvider) {
        this.config = config;
        this.executorProvider = executorProvider;
        this.ownsExecutorProvider = ownsExecutorProvider;
        this.diffSink = new InMemoryDiffSink();
    }

    @Override
    public void setDiffSink(DiffSink diffSink) {
        this.diffSink = diffSink != null ? diffSink : DiffSink.noop();
    }

    @Override
    public DiffSink getDiffSink() {
        return diffSink;
    }

    protected List<DiffRow> getCollectedDifferences() {
        if (diffSink instanceof InMemoryDiffSink) {
            return ((InMemoryDiffSink) diffSink).getDifferences();
        }
        return new ArrayList<>();
    }

    protected void ensureDiffLimit(long nextDifferenceCount) {
        if (diffSink instanceof InMemoryDiffSink) {
            long current = ((InMemoryDiffSink) diffSink).size();
            long maxDifferences = config.getMaxDifferences();
            if (current + nextDifferenceCount > maxDifferences) {
                throw new IllegalStateException("Diff result exceeds maxDifferences=" + maxDifferences
                        + ". Increase strategy.maxDifferences or narrow the comparison scope.");
            }
        }
    }

    /**
     * Main entry point for table diffing.
     */
    public CompletableFuture<DiffResult> diffTables(TableSegment table1, TableSegment table2) {
        log.info("Starting diff between {} and {}", table1.getTablePath(), table2.getTablePath());

        try {
            // Align schemas between the two segments before any expensive work
            validateAndAdjustColumns(table1, table2);

            // Build the base info tree that the concrete algorithms can enrich
            InfoTreeRecorder infoTreeRecorder = new InfoTreeRecorder("diff");
            infoTreeRecorder.startNode("diff", null, "diff", "root", "diff", 0);
            infoTreeRecorder.recordGlobalMetric("algorithm", getClass().getSimpleName());
            infoTreeRecorder.recordGlobalMetric("startedAt", Instant.now().toString());

            CompletableFuture<DiffResult> future = diffTablesRoot(table1, table2, infoTreeRecorder);
            return future.whenComplete((result, error) -> {
                        infoTreeRecorder.endNode("diff");
                        infoTreeRecorder.recordGlobalMetric("endedAt", Instant.now().toString());
                        infoTreeRecorder.endTree();
                    })
                    .thenApply(result -> {
                        if (result == null) {
                            return null;
                        }
                        return result.toBuilder()
                                .infoTree(Optional.of(infoTreeRecorder.snapshot()))
                                .build();
                    });

        } catch (Exception e) {
            log.error("Error during table diffing", e);
            return CompletableFuture.failedFuture(new RuntimeException("Table diff failed", e));
        }
    }

    /**
     * Root diff method that delegates to specific algorithm implementation.
     */
    protected abstract CompletableFuture<DiffResult> diffTablesRoot(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder);

    /**
     * Diff specific segments - core algorithm implementation point.
     */
    protected abstract CompletableFuture<Void> diffSegments(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level);

    /**
     * Validate table schemas and adjust for compatibility.
     * Supports tables with different column names but same structure.
     */
    protected void validateAndAdjustColumns(TableSegment table1, TableSegment table2) {
        // Validate key columns count match (names can be different, but count must match)
        if (table1.getKeyColumns().size() != table2.getKeyColumns().size()) {
            log.warn("Table1 key columns: {}, Table2 key columns: {}",
                     table1.getKeyColumns(), table2.getKeyColumns());
            throw new IllegalArgumentException(String.format(
                "Key columns count must match between tables. Table1 has %d, Table2 has %d",
                table1.getKeyColumns().size(), table2.getKeyColumns().size()));
        }

        /*
         * Extra (non-key) columns can differ, but warn when the counts do not align. Algorithms depend on
         * positional comparisons (e.g., LocalDiffEngine) so a mismatch is usually accidental. We still
         * continue to allow asymmetric comparisons, but the log warning draws attention to potential
         * misconfiguration.
         */
        if (table1.getExtraColumns().size() != table2.getExtraColumns().size()) {
            log.warn("Table1 extra columns: {}, Table2 extra columns: {}",
                     table1.getExtraColumns(), table2.getExtraColumns());
            log.info("Extra columns count difference is acceptable for comparison");
            // Don't throw exception - extra columns can be different and still work
        }

        log.info("Column validation completed for tables {} and {} (key columns: {}, extra columns: {} vs {})",
                table1.getTablePath(), table2.getTablePath(),
                table1.getKeyColumns().size(), table1.getExtraColumns().size(), table2.getExtraColumns().size());
    }

    /**
     * Shutdown resources and clean up.
     */
    public void shutdown() {
        try {
            log.info("Shutting down TableDiffer...");
            if (!ownsExecutorProvider) {
                log.debug("Skipping ExecutorProvider shutdown because TableDiffer does not own it");
                return;
            }
            if (executorProvider != null) {
                executorProvider.shutdown();
                log.info("ExecutorProvider shutdown completed");
            }
        } catch (Exception e) {
            log.warn("Error during TableDiffer shutdown", e);
        }
    }

    /**
     * Bisect and diff segments using recursive divide-and-conquer.
     */
    protected CompletableFuture<Void> bisectAndDiffSegments(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            int level) {

        if (!table1.isBounded() || !table2.isBounded()) {
            throw new IllegalArgumentException("Both segments must be bounded for bisection");
        }

        long size1 = table1.approximateSize();
        long size2 = table2.approximateSize();
        
        // If approximateSize returns -1 (unknown), use actual count
        if (size1 < 0) {
            try {
                size1 = table1.count();
            } catch (Exception e) {
                log.warn("Failed to get count for table1, using default", e);
                size1 = config.getBisectionThreshold() * 2;
            }
        }
        if (size2 < 0) {
            try {
                size2 = table2.count();
            } catch (Exception e) {
                log.warn("Failed to get count for table2, using default", e);
                size2 = config.getBisectionThreshold() * 2;
            }
        }

        log.debug("Bisecting segments at level {}, size: table1 <= {}, table2 <= {}",
                level, size1, size2);

        // Choose checkpoints for segmentation
        // Split the table with larger estimated range to keep partitions aligned
        TableSegment largerTable = size1 >= size2 ? table1 : table2;
        List<List<Object>> checkpoints = largerTable.chooseCheckpoints(config.getBisectionFactor() - 1);

        // Create segmented tables
        List<TableSegment> segmented1 = table1.segmentByCheckpoints(checkpoints);
        List<TableSegment> segmented2 = table2.segmentByCheckpoints(checkpoints);

        // Kick off recursive diffing for each paired child segment
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < segmented1.size(); i++) {
            TableSegment t1 = segmented1.get(i);
            TableSegment t2 = segmented2.get(i);

            long segSize1 = t1.approximateSize();
            long segSize2 = t2.approximateSize();
            
            // If approximateSize returns -1 (unknown), use actual count
            if (segSize1 < 0) {
                try {
                    segSize1 = t1.count();
                } catch (Exception e) {
                    segSize1 = config.getBisectionThreshold();
                }
            }
            if (segSize2 < 0) {
                try {
                    segSize2 = t2.count();
                } catch (Exception e) {
                    segSize2 = config.getBisectionThreshold();
                }
            }
            
            long maxRows = Math.max(segSize1, segSize2);

            // Delegate the actual comparison for this slice to the concrete implementation
            CompletableFuture<Void> future = diffSegments(t1, t2, infoTreeRecorder, maxRows, level + 1);
            futures.add(future);
        }

        // Wait for all segments to complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Configuration for diff algorithms.
     */
    @Getter
    public static class DifferConfig {
        private final int bisectionFactor;
        private final long bisectionThreshold;
        private final boolean enableProfiling;
        private final int maxDepth;
        private final ChecksumAlgorithm checksumAlgorithm;
        private final LocalCompareMode localCompareMode;
        private final ConcurrencyConfig concurrencyConfig;
        private final long maxDifferences;

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           boolean enableProfiling, ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences) {
            this.bisectionFactor = bisectionFactor;
            this.bisectionThreshold = bisectionThreshold;
            this.enableProfiling = enableProfiling;
            this.maxDepth = 10;
            this.checksumAlgorithm = checksumAlgorithm != null ? checksumAlgorithm : ChecksumAlgorithm.CONCAT;
            this.localCompareMode = localCompareMode != null ? localCompareMode : LocalCompareMode.FULL;
            this.concurrencyConfig = concurrencyConfig != null ? concurrencyConfig : ConcurrencyConfig.defaultConfig();
            this.maxDifferences = maxDifferences > 0 ? maxDifferences : 1_000_000L;
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           boolean enableProfiling, ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold, enableProfiling,
                 checksumAlgorithm, localCompareMode, concurrencyConfig, 1_000_000L);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           boolean enableProfiling, ChecksumAlgorithm checksumAlgorithm) {
            this(bisectionFactor, bisectionThreshold, enableProfiling,
                 checksumAlgorithm, LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           boolean enableProfiling, ChecksumAlgorithm checksumAlgorithm,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold, enableProfiling,
                 checksumAlgorithm, LocalCompareMode.FULL, concurrencyConfig);
        }

        public static DifferConfig defaultConfig() {
            return new DifferConfig(32, 16384, false, ChecksumAlgorithm.CONCAT,
                    LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

    }
}
