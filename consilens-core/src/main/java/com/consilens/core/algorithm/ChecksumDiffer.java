package com.consilens.core.algorithm;

import com.consilens.common.enums.LocalCompareMode;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.segment.TableSegment.ChecksumResult;
import com.consilens.core.segment.TableSegmenter;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.thread.ExecutorProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TableSchema;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced hash-based table differ implementation.
 */
@Slf4j
public class ChecksumDiffer extends TableDiffer implements AutoCloseable {

    private final ChecksumCache checksumCache;
    private final PerformanceMonitor performanceMonitor;
    private final AtomicLong sourceMissingCount = new AtomicLong(0);
    private final AtomicLong targetMissingCount = new AtomicLong(0);
    private final AtomicLong mismatchCount = new AtomicLong(0);
    private final ProgressReporter progressReporter;
    private final AtomicLong segmentSequence = new AtomicLong(0);
    private final Map<DatabaseAdapter, TableSegmenter> segmenterCache = new ConcurrentHashMap<>();
    private final SegmentPermitController activeSegmentBudget;

    /**
     * Creates a new ChecksumDiffer with the given configuration.
     *
     * @param config algorithm configuration including bisection factor, threshold, checksum algorithm, and concurrency settings
     * @throws IllegalArgumentException if bisectionFactor &gt;= bisectionThreshold or bisectionFactor &lt; 2
     */
    public ChecksumDiffer(DifferConfig config) {
        this(config, null);
    }

    public ChecksumDiffer(DifferConfig config, ExecutorProvider executorProvider) {
        super(config,
                executorProvider != null ? executorProvider : new ExecutorProvider(config.getConcurrencyConfig()),
                executorProvider == null);

        // Prevent degenerate recursion settings that would explode tasks or never split
        if (config.getBisectionThreshold() <= 0) {
            throw new IllegalArgumentException("Bisection threshold must be greater than 0");
        }
        if (config.getBisectionFactor() >= config.getBisectionThreshold()) {
            throw new IllegalArgumentException("Bisection factor must be lower than threshold");
        }
        if (config.getBisectionFactor() < 2) {
            throw new IllegalArgumentException("Bisection factor must be at least 2");
        }

        this.checksumCache = new ChecksumCache();
        this.performanceMonitor = new PerformanceMonitor();
        this.progressReporter = new ProgressReporter();
        int segmentBudget = resolveSegmentBudget(config);
        this.activeSegmentBudget = new SegmentPermitController(segmentBudget);
        
        log.info("ChecksumDiffer initialized with checksumAlgorithm: {}, local comparison mode: auto, segmentBudget={}",
                config.getChecksumAlgorithm(), segmentBudget);
    }

    @Override
    protected CompletableFuture<DiffResult> diffTablesRoot(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder) {

        log.info("Starting Enhanced ChecksumDiff between {} and {}",
                table1.getTablePath(), table2.getTablePath());

        // Track total runtime for observability dashboards
        performanceMonitor.startDiff("root");
        infoTreeRecorder.startNode("checksum-diff", "diff", "checksum-diff", "algorithm", "checksum-diff", 1);
        infoTreeRecorder.recordRange("checksum-diff",
                table1.getMinKey().map(Object::toString).orElse(null),
                table1.getMaxKey().map(Object::toString).orElse(null),
                table1.getWhereClause().orElse(null));

        // Initialize progress reporting
        progressReporter.initialize(table1, table2, config.getBisectionThreshold());

        // Step 1: Get initial statistics and establish bounds
        return getInitialBounds(table1, table2)
                .thenCompose(bounds -> {
                    performanceMonitor.recordStage("bounds_established");

                    // Step 2: Create bounded segments
                    // Clamp both tables to the discovered min/max keys so recursion has bounded space
                    TableSegment bounded1 = createBoundedSegment(table1, bounds.table1Bounds);
                    TableSegment bounded2 = createBoundedSegment(table2, bounds.table2Bounds);

                    // Step 3: Check if we should compare directly without bisection
                    long totalRows = bounds.table1Bounds.getCount() + bounds.table2Bounds.getCount();
                    if (shouldCompareLocally(totalRows, bounds.maxRows, 0)) {
                        log.info("Total rows ({}) below threshold ({}), comparing directly without bisection", 
                                totalRows, config.getBisectionThreshold());
                        // Compare directly without bisection
                        return diffSegmentsWithParent(bounded1, bounded2, infoTreeRecorder, bounds.maxRows, 0, "checksum-diff")
                                .thenApply(result -> {
                                    performanceMonitor.endDiff("root");
                                    progressReporter.complete();
                                    infoTreeRecorder.endNode("checksum-diff");

                                    Long durationMs = (Long) performanceMonitor.metrics.get("root_duration");

                                    return createDiffResult(table1, table2, result, bounds.table1Bounds.getCount(),
                                            bounds.table2Bounds.getCount(), durationMs != null ? durationMs : 0L);
                                });
                    }

                    // Step 4: Start recursive bisection
                    return bisectAndDiffSegmentsEnhanced(
                            bounded1, bounded2, infoTreeRecorder, 0, bounds.maxRows, config.getBisectionFactor(), "checksum-diff")
                            .thenApply(result -> {
                                performanceMonitor.endDiff("root");
                                progressReporter.complete();
                                infoTreeRecorder.endNode("checksum-diff");

                                // Retrieve the total diff duration
                                Long durationMs = (Long) performanceMonitor.metrics.get("root_duration");

                                return createDiffResult(table1, table2, result, bounds.table1Bounds.getCount(),
                                        bounds.table2Bounds.getCount(), durationMs != null ? durationMs : 0L);
                            });
                })
                .exceptionally(e -> {
                    performanceMonitor.endDiff("root");
                    progressReporter.shutdown();
                    infoTreeRecorder.endNode("checksum-diff");
                    log.error("ChecksumDiff failed", e);
                    throw new RuntimeException("ChecksumDiff failed", e);
                });
    }

    @Override
    protected CompletableFuture<Void> diffSegments(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level) {
        return diffSegmentsWithParent(table1, table2, infoTreeRecorder, maxRows, level, "checksum-diff");
    }

    private CompletableFuture<Void> diffSegmentsWithParent(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level,
            String parentSegmentId) {

        String segmentId = generateSegmentId(table1, table2, level);
        performanceMonitor.startDiff(segmentId);
        infoTreeRecorder.startNode(segmentId, parentSegmentId, "segment", "segment", segmentId, level);
        infoTreeRecorder.recordRange(segmentId,
                table1.getMinKey().map(Object::toString).orElse(null),
                table1.getMaxKey().map(Object::toString).orElse(null),
                table1.getWhereClause().orElse(null));
        infoTreeRecorder.recordMetric(segmentId, "maxRows", maxRows);

        log.info("Diffing segment {} at level {}, size: {}, algorithm: {}", 
                segmentId, level, maxRows, config.getChecksumAlgorithm());

        return diffSegmentsWithChecksum(table1, table2, infoTreeRecorder, maxRows, level, segmentId)
                .handle((result, error) -> {
                    infoTreeRecorder.endNode(segmentId);
                    if (error != null) {
                        throw propagateAsyncFailure(error);
                    }
                    return result;
                });
    }

    /**
     * Diff segments using Checksum (legacy mode).
     */
    private CompletableFuture<Void> diffSegmentsWithChecksum(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level,
            String segmentId) {

        // Step 1: Calculate checksums for both segments (with caching)
        CompletableFuture<ChecksumResult> checksum1Future = getChecksumWithCache(table1);
        CompletableFuture<ChecksumResult> checksum2Future = getChecksumWithCache(table2);

        return CompletableFuture.allOf(checksum1Future, checksum2Future)
                .thenCompose(v -> {
                    try {
                        ChecksumResult result1 = checksum1Future.get();
                        ChecksumResult result2 = checksum2Future.get();

                        performanceMonitor.recordChecksum(segmentId, result1, result2);

                        // Step 2: Quick elimination checks
                        infoTreeRecorder.recordCounts(segmentId, result1.getCount(), result2.getCount());

                        if (result1.getCount() == 0 && result2.getCount() == 0) {
                            log.info("Both segments {} empty - no differences", segmentId);
                            infoTreeRecorder.recordDecision(segmentId, "empty_segment");
                            infoTreeRecorder.markSkippedSegment();
                            performanceMonitor.endDiff(segmentId);
                            return CompletableFuture.completedFuture(null);
                        }

                        if (result1.getCount() == result2.getCount()
                                && result1.getChecksum() != null
                                && result1.getChecksum().equals(result2.getChecksum())) {
                            log.info("Checksums match for segment {} - no differences", segmentId);
                            infoTreeRecorder.recordDecision(segmentId, "checksum_equal");
                            infoTreeRecorder.markSkippedSegment();
                            performanceMonitor.endDiff(segmentId);
                            progressReporter.segmentCompleted(segmentId,
                                    result1.getCount() + result2.getCount(), 0);
                            return CompletableFuture.completedFuture(null);
                        }

                        // Step 3: Determine next action based on segment size and characteristics
                        return determineSegmentAction(table1, table2, result1, result2, maxRows, level, infoTreeRecorder,
                                segmentId);

                    } catch (Exception e) {
                        performanceMonitor.endDiff(segmentId);
                        throw new RuntimeException("Failed to diff segment: " + segmentId, e);
                    }
                });
    }

    private CompletableFuture<Void> determineSegmentAction(
            TableSegment table1, TableSegment table2,
            ChecksumResult result1, ChecksumResult result2,
            long maxRows, int level, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        // Calculate segment characteristics
        long totalRows = result1.getCount() + result2.getCount();
        long minCount = Math.min(result1.getCount(), result2.getCount());
        double sizeRatio = minCount == 0
                ? (Math.max(result1.getCount(), result2.getCount()) == 0 ? 1.0 : Double.POSITIVE_INFINITY)
                : Math.max(result1.getCount(), result2.getCount()) / (double) minCount;

        /*
         * Decision tree for segment processing:
         * 1. If the combined row count is tiny or recursion is already deep (see shouldCompareLocally),
         *    avoid further JDBC round-trips and instead pull the rows into memory for direct comparison.
         * 2. If the size ratio between tables is large (>3x) or the totals dwarf the configured threshold,
         *    request a larger number of checkpoints (adaptive segmentation) so the larger side is sliced
         *    into finer-grained ranges than the smaller side.
         * 3. Otherwise perform a standard bisection which splits the current range evenly.
         */
        if (shouldCompareLocally(totalRows, maxRows, level)) {
            // Small enough for local comparison
            infoTreeRecorder.recordDecision(segmentId, "local_compare");
            infoTreeRecorder.markLocalCompareSegment();
            return performLocalComparison(table1, table2, infoTreeRecorder, segmentId);
        } else if (shouldUseAdaptiveSegmentation(totalRows, maxRows, sizeRatio, level)) {
            // Use adaptive segmentation
            infoTreeRecorder.recordDecision(segmentId, "adaptive_split");
            return performAdaptiveSegmentation(table1, table2, infoTreeRecorder, level, segmentId);
        } else {
            // Standard bisection
            infoTreeRecorder.recordDecision(segmentId, "standard_split");
            return performStandardBisection(table1, table2, infoTreeRecorder, level, maxRows, segmentId);
        }
    }

    /**
     * Enhanced bisection with adaptive strategy.
     */
    private CompletableFuture<Void> bisectAndDiffSegmentsEnhanced(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            int level,
            long maxRows,
            int bisectionFactor,
            String parentSegmentId) {

        // Explicit recursion depth guard
        if (level > config.getMaxDepth()) {
            log.warn("Recursion depth {} exceeds maxDepth {}, forcing local comparison for segment {}",
                    level, config.getMaxDepth(), parentSegmentId);
            return performLocalComparison(table1, table2, infoTreeRecorder, parentSegmentId);
        }

        if (!table1.isBounded() || !table2.isBounded()) {
            log.debug("Creating initial bounds for unbounded segments at level {}", level);
            return createInitialBoundsAndBisection(table1, table2, infoTreeRecorder, level, bisectionFactor, parentSegmentId);
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

        log.debug("Enhanced bisection at level {}, sizes: table1={}, table2={}, factor={}",
                level, size1, size2, bisectionFactor);

        // Log database adapters for debugging
        log.debug("Table1 database: {}, Table2 database: {}", 
                table1.getDatabase() != null ? table1.getDatabase().getName() : "null",
                table2.getDatabase() != null ? table2.getDatabase().getName() : "null");

        // Choose optimal segmentation strategy
        TableSegment largerTable = size1 >= size2 ? table1 : table2;
        TableSegment smallerTable = largerTable == table1 ? table2 : table1;

        log.debug("Larger table: {}, Smaller table: {}", 
                largerTable.getTablePath(), smallerTable.getTablePath());

        // Create a dedicated segmenter for the larger table to avoid database adapter confusion
        TableSegmenter largerTableSegmenter = getOrCreateSegmenter(largerTable);

        return largerTableSegmenter.createOptimalSegments(largerTable, bisectionFactor, config.getBisectionThreshold())
                .thenCompose(largerSegments -> {
                    // Log the segments created
                    log.debug("Created {} segments from larger table", largerSegments.size());
                    for (int i = 0; i < Math.min(3, largerSegments.size()); i++) {
                        TableSegment seg = largerSegments.get(i);
                        log.debug("  Segment {}: database={}", i, 
                                seg.getDatabase() != null ? seg.getDatabase().getName() : "null");
                    }

                    if (largerSegments.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    // Create corresponding segments for the smaller table and process all segments.
                    // Each child segment acquires its own permit and waits in FIFO order when the budget is full.
                    return createCorrespondingSegmentsAndProcess(smallerTable, largerSegments, table1, table2,
                            largerTable, infoTreeRecorder, level, parentSegmentId);
                });
    }

    /**
     * Get initial bounds for tables.
     */
    private CompletableFuture<InitialBounds> getInitialBounds(TableSegment table1, TableSegment table2) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get initial bounds for the segments
                // Note: We keep the WHERE clause if it exists, so bounds are calculated within the filtered range
                TableSegment unboundedTable1 = table1.toBuilder()
                        .minKey(Optional.empty())
                        .maxKey(Optional.empty())
                        // Keep whereClause from original segment
                        .build();
                
                TableSegment unboundedTable2 = table2.toBuilder()
                        .minKey(Optional.empty())
                        .maxKey(Optional.empty())
                        // Keep whereClause from original segment
                        .build();
                
                // Use countAndBounds() instead of countAndChecksum() for faster initialization
                // We only need count, minKey, maxKey - no need for expensive checksum calculation
                ChecksumResult bounds1 = unboundedTable1.countAndBounds();
                ChecksumResult bounds2 = unboundedTable2.countAndBounds();

                long maxRows = Math.max(bounds1.getCount(), bounds2.getCount());

                log.info("Initial bounds established - Table1: count={}, minKey={}, maxKey={}", 
                        bounds1.getCount(), bounds1.getMinKey(), bounds1.getMaxKey());
                log.info("Initial bounds established - Table2: count={}, minKey={}, maxKey={}", 
                        bounds2.getCount(), bounds2.getMinKey(), bounds2.getMaxKey());

                return new InitialBounds(bounds1, bounds2, maxRows);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get initial bounds", e);
            }
        }, executorProvider.getIoExecutor());
    }

    /**
     * Get checksum with caching support.
     */
    private CompletableFuture<ChecksumResult> getChecksumWithCache(TableSegment segment) {
        String cacheKey = generateChecksumCacheKey(segment);

        ChecksumResult cached = checksumCache.get(cacheKey);
        if (cached != null) {
            // Avoid round-trips for hot ranges (e.g., when the same boundary resurfaces after recursion).
            log.debug("Using cached checksum for segment: {}", segment.getTablePath());
            return CompletableFuture.completedFuture(cached);
        }

        // Offload the checksum computation to the executor so the caller's thread can keep orchestrating other work.
        return CompletableFuture.supplyAsync(() -> {
            ChecksumResult result = segment.countAndChecksum();
            checksumCache.put(cacheKey, result);
            return result;
        }, executorProvider.getIoExecutor());
    }

    /**
     * Create bounded segment from checksum result.
     * 
     * CRITICAL: We need to set both minKey and maxKey to satisfy isBounded() check,
     * but the original whereClause will ensure the correct upper bound (using <=).
     * The maxKey will generate "< maxKey" in buildWhereClause(), but the whereClause
     * will override it with the correct "<= maxKey" condition.
     */
    private TableSegment createBoundedSegment(TableSegment original, ChecksumResult bounds) {
        if (bounds == null || bounds.getMinKey() == null || bounds.getMaxKey() == null) {
            return original;
        }

        // Set both minKey and maxKey to satisfy isBounded() requirement
        // The original whereClause will be preserved and will provide the correct upper bound
        return original.toBuilder()
                .minKey(Optional.of(bounds.getMinKey()))
                .maxKey(Optional.of(bounds.getMaxKey()))
                .build();
    }

    private RuntimeException propagateAsyncFailure(Throwable error) {
        Throwable unwrapped = unwrapAsyncFailure(error);
        if (unwrapped instanceof RuntimeException) {
            return (RuntimeException) unwrapped;
        }
        return new RuntimeException(unwrapped);
    }

    private Throwable unwrapAsyncFailure(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private TableSegmenter getOrCreateSegmenter(TableSegment table) {
        DatabaseAdapter database = table.getDatabase();
        if (database == null) {
            return new TableSegmenter(null, TableSegmenter.SegmenterConfig.defaultConfig(), executorProvider);
        }
        return segmenterCache.computeIfAbsent(database,
                db -> new TableSegmenter(db, TableSegmenter.SegmenterConfig.defaultConfig(), executorProvider));
    }

    private int resolveSegmentBudget(DifferConfig config) {
        if (config == null || config.getConcurrencyConfig() == null || config.getConcurrencyConfig().getIo() == null) {
            return 64;
        }
        ConcurrencyConfig.PoolConfig ioConfig = config.getConcurrencyConfig().getIo();
        int executorCapacity = Math.max(ioConfig.getCore(), ioConfig.getMax());
        return Math.max(64, executorCapacity * 4);
    }

    /**
     * Check if segment should be compared locally.
     */
    private boolean shouldCompareLocally(long totalRows, long maxRows, int level) {
        // Heuristics:
        // - totalRows < bisectionThreshold: the slice is already smaller than what we'd normally split, so recurse no further.
        // - totalRows < (bisectionFactor * 2): even if the threshold is large, a very small slice does not benefit from hashing.
        // - level > maxDepth: guardrail to prevent runaway recursion; force a terminal comparison.
        return totalRows < config.getBisectionThreshold()
                || totalRows < config.getBisectionFactor() * 2L
                || level > config.getMaxDepth();
    }

    /**
     * Check if adaptive segmentation should be used.
     */
    private boolean shouldUseAdaptiveSegmentation(long totalRows, long maxRows, double sizeRatio, int level) {
        // Multi-split only pays off when the current mismatched range is still substantially larger
        // than a single configured threshold window. Otherwise we should switch to real bisection.
        if (!shouldContinueMultiSplit(maxRows, level)) {
            return false;
        }

        // For very skewed slices, or for extremely large ranges near the top of the tree,
        // keep the wider adaptive fan-out to converge quickly before falling back to binary search.
        return sizeRatio > 3.0
                || (totalRows > config.getBisectionThreshold() * 10 && level < 3);
    }

    private boolean shouldContinueMultiSplit(long maxRows, int level) {
        if (level <= 0) {
            return true;
        }

        long multiSplitThreshold = config.getBisectionThreshold() * (long) config.getBisectionFactor();
        return maxRows > multiSplitThreshold;
    }

    /**
     * Perform local comparison of segments.
     * 
     * If configured, use row-level hashes for difference detection.
     * This avoids pulling all column data when most rows are identical.
     */
    private CompletableFuture<Void> performLocalComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        LocalCompareMode localCompareMode = config.getLocalCompareMode();
        log.info("Performing local comparison for segment: {} using mode: {}", segmentId, localCompareMode);
        infoTreeRecorder.recordMetric(segmentId, "localCompareMode", localCompareMode.getCode());

        if (localCompareMode.isRowHash()) {
            return performRowHashBasedComparison(table1, table2, infoTreeRecorder, segmentId);
        } else {
            return performFullDataComparison(table1, table2, infoTreeRecorder, segmentId);
        }
    }

    /**
     * Perform row-hash based comparison.
     * 
     * <p>Algorithm:
     * <ol>
     *   <li>Query row hashes (primary key + hash) from both tables - lightweight</li>
     *   <li>Compare hashes in memory to find mismatched keys</li>
     *   <li>Query full row data only for mismatched keys</li>
     * </ol>
     * 
     * <p>Performance: For tables with many columns and few differences, this is 10x-100x faster
     * than pulling all data.
     */
    private CompletableFuture<Void> performRowHashBasedComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Using row-hash based comparison for segment: {}", segmentId);
            Map<List<Object>, String> hashes1 = table1.getRowHashes();
            Map<List<Object>, String> hashes2 = table2.getRowHashes();
            return new RowHashSnapshot(hashes1, hashes2);
        }, executorProvider.getIoExecutor())
                .thenApplyAsync(snapshot -> {
                    Set<List<Object>> keysOnlyInTable1 = new HashSet<>(snapshot.hashes1.keySet());
                    keysOnlyInTable1.removeAll(snapshot.hashes2.keySet());

                    Set<List<Object>> keysOnlyInTable2 = new HashSet<>(snapshot.hashes2.keySet());
                    keysOnlyInTable2.removeAll(snapshot.hashes1.keySet());

                    Set<List<Object>> mismatchedKeys = new HashSet<>();
                    for (Map.Entry<List<Object>, String> entry : snapshot.hashes1.entrySet()) {
                        List<Object> key = entry.getKey();
                        if (snapshot.hashes2.containsKey(key)) {
                            String hash1 = entry.getValue();
                            String hash2 = snapshot.hashes2.get(key);
                            if (!hash1.equals(hash2)) {
                                mismatchedKeys.add(key);
                            }
                        }
                    }

                    int totalDifferences = keysOnlyInTable1.size() + keysOnlyInTable2.size() + mismatchedKeys.size();
                    log.info("Hash comparison found {} differences: {} only in table1, {} only in table2, {} mismatched",
                            totalDifferences, keysOnlyInTable1.size(), keysOnlyInTable2.size(), mismatchedKeys.size());

                    // Log first few mismatched keys for diagnosis
                    if (!mismatchedKeys.isEmpty()) {
                        int debugCount = 0;
                        for (List<Object> key : mismatchedKeys) {
                            if (debugCount++ < 5) {
                                String hash1 = snapshot.hashes1.get(key);
                                String hash2 = snapshot.hashes2.get(key);
                                log.debug("Hash mismatch for key {}: source_hash={}, target_hash={}",
                                        key, hash1, hash2);
                            } else {
                                break;
                            }
                        }
                    }

                    return new RowChecksumDiffPlan(snapshot, keysOnlyInTable1, keysOnlyInTable2, mismatchedKeys);
                }, executorProvider.getCpuExecutor())
                .thenApplyAsync(plan -> {
                    if (plan.totalDifferences() == 0) {
                        return new RowChecksumDiffData(plan, Collections.emptyMap(), Collections.emptyMap());
                    }
                    Map<List<Object>, Object[]> data1 = queryRowsByKeys(table1,
                            combineKeys(plan.keysOnlyInTable1, plan.mismatchedKeys));
                    Map<List<Object>, Object[]> data2 = queryRowsByKeys(table2,
                            combineKeys(plan.keysOnlyInTable2, plan.mismatchedKeys));
                    return new RowChecksumDiffData(plan, data1, data2);
                }, executorProvider.getIoExecutor())
                .thenAcceptAsync(diffData -> {
                    try {
                        RowChecksumDiffPlan plan = diffData.plan;
                        List<DiffRow> differences = new ArrayList<>();

                        if (plan.totalDifferences() > 0) {
                            Map<String, DataType> columnTypes1 = extractColumnTypes(table1);
                            Map<String, DataType> columnTypes2 = extractColumnTypes(table2);

                            for (List<Object> key : plan.keysOnlyInTable1) {
                                Object[] row = diffData.data1.get(key);
                                if (row != null) {
                                    differences.add(createDiffRow(key, row, null,
                                            table1.getKeyColumns(), table1.getExtraColumns(),
                                            DiffOperation.TARGET_MISSING, columnTypes1, columnTypes2));
                                }
                            }

                            for (List<Object> key : plan.keysOnlyInTable2) {
                                Object[] row = diffData.data2.get(key);
                                if (row != null) {
                                    differences.add(createDiffRow(key, null, row,
                                            table2.getKeyColumns(), table2.getExtraColumns(),
                                            DiffOperation.SOURCE_MISSING, columnTypes1, columnTypes2));
                                }
                            }

                            for (List<Object> key : plan.mismatchedKeys) {
                                Object[] row1 = diffData.data1.get(key);
                                Object[] row2 = diffData.data2.get(key);
                                if (row1 != null && row2 != null) {
                                    differences.add(createDiffRow(key, row1, row2,
                                            table1.getKeyColumns(), table1.getExtraColumns(),
                                            DiffOperation.MISMATCH, columnTypes1, columnTypes2));
                                }
                            }
                        }

                        performanceMonitor.recordLocalComparison(segmentId,
                                plan.snapshot.hashes1.size(), plan.snapshot.hashes2.size(), differences.size());

                        storeDifferences(infoTreeRecorder, differences, segmentId);
                        infoTreeRecorder.addRowsFetched(segmentId,
                                plan.snapshot.hashes1.size() + plan.snapshot.hashes2.size());

                        progressReporter.segmentCompleted(segmentId,
                                plan.snapshot.hashes1.size() + plan.snapshot.hashes2.size(), differences.size());

                        log.info("Row-hash based comparison completed: {} differences for segment: {}",
                                differences.size(), segmentId);
                    } catch (Exception e) {
                        log.error("Row-hash based comparison failed for segment: {}", segmentId, e);
                        throw new RuntimeException("Row-hash based comparison failed", e);
                    }
                }, executorProvider.getCpuExecutor());
    }

    /**
     * Perform full data comparison.
     */
    private CompletableFuture<Void> performFullDataComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Using full data comparison for segment: {}", segmentId);
            List<Object[]> rows1 = table1.getValues();
            List<Object[]> rows2 = table2.getValues();
            return new FullDataSnapshot(rows1, rows2);
        }, executorProvider.getIoExecutor())
                .thenAcceptAsync(snapshot -> {
                    try {
                        Map<String, DataType> columnTypes1 = extractColumnTypes(table1);
                        Map<String, DataType> columnTypes2 = extractColumnTypes(table2);

                        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                snapshot.rows1, snapshot.rows2,
                                table1.getKeyColumns(),
                                table1.getExtraColumns(),
                                table2.getKeyColumns(),
                                table2.getExtraColumns(),
                                columnTypes1,
                                columnTypes2);

                        performanceMonitor.recordLocalComparison(segmentId,
                                snapshot.rows1.size(), snapshot.rows2.size(), differences.size());

                        storeDifferences(infoTreeRecorder, differences, segmentId);
                        infoTreeRecorder.addRowsFetched(segmentId, snapshot.rows1.size() + snapshot.rows2.size());

                        progressReporter.segmentCompleted(segmentId,
                                snapshot.rows1.size() + snapshot.rows2.size(), differences.size());

                        log.info("Full data comparison found {} differences for segment: {}", differences.size(), segmentId);
                    } catch (Exception e) {
                        log.error("Full data comparison failed for segment: {}", segmentId, e);
                        throw new RuntimeException("Full data comparison failed", e);
                    }
                }, executorProvider.getCpuExecutor());
    }

    /**
     * Query rows by primary keys and return as a map.
     */
    private Map<List<Object>, Object[]> queryRowsByKeys(TableSegment segment, Set<List<Object>> keys) {
        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object[]> rows = segment.getValuesByKeys(keys);
        Map<List<Object>, Object[]> rowMap = new HashMap<>();

        int keyColumnCount = segment.getKeyColumns().size();

        for (Object[] row : rows) {
            // Extract primary key from row
            List<Object> primaryKey = new ArrayList<>(keyColumnCount);
            primaryKey.addAll(Arrays.asList(row).subList(0, keyColumnCount));
            rowMap.put(primaryKey, row);
        }

        return rowMap;
    }

    private static class RowHashSnapshot {
        private final Map<List<Object>, String> hashes1;
        private final Map<List<Object>, String> hashes2;

        private RowHashSnapshot(Map<List<Object>, String> hashes1, Map<List<Object>, String> hashes2) {
            this.hashes1 = hashes1;
            this.hashes2 = hashes2;
        }
    }

    private static class RowChecksumDiffPlan {
        private final RowHashSnapshot snapshot;
        private final Set<List<Object>> keysOnlyInTable1;
        private final Set<List<Object>> keysOnlyInTable2;
        private final Set<List<Object>> mismatchedKeys;

        private RowChecksumDiffPlan(RowHashSnapshot snapshot, Set<List<Object>> keysOnlyInTable1,
                                    Set<List<Object>> keysOnlyInTable2, Set<List<Object>> mismatchedKeys) {
            this.snapshot = snapshot;
            this.keysOnlyInTable1 = keysOnlyInTable1;
            this.keysOnlyInTable2 = keysOnlyInTable2;
            this.mismatchedKeys = mismatchedKeys;
        }

        private int totalDifferences() {
            return keysOnlyInTable1.size() + keysOnlyInTable2.size() + mismatchedKeys.size();
        }
    }

    private static class RowChecksumDiffData {
        private final RowChecksumDiffPlan plan;
        private final Map<List<Object>, Object[]> data1;
        private final Map<List<Object>, Object[]> data2;

        private RowChecksumDiffData(RowChecksumDiffPlan plan, Map<List<Object>, Object[]> data1,
                                    Map<List<Object>, Object[]> data2) {
            this.plan = plan;
            this.data1 = data1;
            this.data2 = data2;
        }
    }

    private static class FullDataSnapshot {
        private final List<Object[]> rows1;
        private final List<Object[]> rows2;

        private FullDataSnapshot(List<Object[]> rows1, List<Object[]> rows2) {
            this.rows1 = rows1;
            this.rows2 = rows2;
        }
    }

    /**
     * Combine multiple sets of keys into one.
     */
    private Set<List<Object>> combineKeys(Set<List<Object>>... keySets) {
        Set<List<Object>> combined = new HashSet<>();
        for (Set<List<Object>> keySet : keySets) {
            combined.addAll(keySet);
        }
        return combined;
    }

    /**
     * Create a DiffRow from row data.
     * 
     * BUGFIX: Include ALL columns (key + extra) in sourceValues/targetValues and columnNames
     * so that output services can access primary key values via getSourceValue()/getTargetValue().
     * 
     * CRITICAL: This method now supports type-aware comparison using ValueNormalizer
     * to ensure consistency with database-side normalization (especially for timestamps).
     */
    private DiffRow createDiffRow(List<Object> primaryKey, Object[] sourceRow, Object[] targetRow,
            List<String> keyColumns, List<String> extraColumns, DiffOperation operation,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {
        
        DiffRow.DiffRowBuilder builder = DiffRow.builder()
                .primaryKey(primaryKey)
                .operation(operation);

        // Combine key columns and extra columns to get all column names
        List<String> allColumns = new ArrayList<>();
        allColumns.addAll(keyColumns);
        allColumns.addAll(extraColumns);

        List<Object> sourceValues = new ArrayList<>();
        List<Object> targetValues = new ArrayList<>();
        List<String> changedColumns1 = new ArrayList<>();
        List<String> changedColumns2 = new ArrayList<>();

        if (sourceRow != null) {
            // Include ALL columns (key + extra)
            sourceValues.addAll(Arrays.asList(sourceRow));
            builder.sourceValues(Optional.of(sourceValues));
        } else {
            builder.sourceValues(Optional.empty());
        }

        if (targetRow != null) {
            // Include ALL columns (key + extra)
            Collections.addAll(targetValues, targetRow);
            builder.targetValues(Optional.of(targetValues));
        } else {
            builder.targetValues(Optional.empty());
        }

        // For MISMATCH operation, identify which columns changed
        // Only compare non-key columns (extra columns)
        if (operation == DiffOperation.MISMATCH && sourceRow != null && targetRow != null) {
            int keyColumnCount = keyColumns.size();
            int minLength = Math.min(sourceRow.length, targetRow.length);
            
            // CRITICAL: The rows now contain database-normalized values (strings)
            // Row structure: [pk1, pk2, ..., normalized_col1, normalized_col2, ...]
            // Direct string comparison is sufficient - no need for ValueNormalizer
            
            for (int i = keyColumnCount; i < minLength; i++) {
                Object sourceValue = sourceRow[i];
                Object targetValue = targetRow[i];
                
                // Direct comparison of normalized strings
                boolean isDifferent = !Objects.equals(sourceValue, targetValue);
                
                if (isDifferent) {
                    // Get column name from allColumns
                    String columnName = (i < allColumns.size()) ? allColumns.get(i) : null;
                    
                    if (columnName != null) {
                        changedColumns1.add(columnName);
                        changedColumns2.add(columnName);

                        log.debug("Column '{}' mismatch for PK={}: " +
                                "Source[normalized='{}'] vs Target[normalized='{}']",
                                columnName, primaryKey, sourceValue, targetValue);
                    } else {
                        log.debug("Column index {} mismatch for PK={}: {} vs {}",
                                i, primaryKey, sourceValue, targetValue);
                    }
                }
            }
            
            log.debug("Identified {} changed columns for primary key {}: {}", 
                    changedColumns1.size(), primaryKey, changedColumns1);
        }

        // Set column names to ALL columns (key + extra)
        builder.columnNames1(allColumns);
        builder.columnNames2(allColumns);
        
        // Set metadata with changed columns
        Map<String, Object> metadata = new HashMap<>();
        if (!changedColumns1.isEmpty()) {
            metadata.put("changedColumns1", changedColumns1);
            metadata.put("changedColumns2", changedColumns2);
        }
        builder.metadata(metadata);

        return builder.build();
    }

    /**
     * Extract column types from table segment schema.
     */
    private Map<String, DataType> extractColumnTypes(TableSegment segment) {
        Map<String, DataType> types = new HashMap<>();
        Optional<TableSchema> schema = segment.getSchema();

        if (schema.isPresent()) {
            // Only expose datatypes for relevant columns so the local diff can normalize values correctly.
            for (String column : segment.getRelevantColumns()) {
                DataType type = schema.get().getColumnType(column);
                if (type != null) {
                    types.put(column, type);
                }
            }
        }
        return types;
    }

    /**
     * Perform adaptive segmentation.
     */
    private CompletableFuture<Void> performAdaptiveSegmentation(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder,
            int level, String segmentId) {

        log.debug("Performing adaptive segmentation for segment: {}", segmentId);

        // Use different segmentation factor based on segment characteristics
        int adaptiveFactor = calculateAdaptiveFactor(table1, table2);
        log.debug("Using adaptive factor {} for segmentation", adaptiveFactor);

        // Pass the adaptive factor to the enhanced bisection method so skewed partitions split faster.
        infoTreeRecorder.recordSplit(segmentId, "adaptive", adaptiveFactor);
        infoTreeRecorder.markAdaptiveSegment();
        return bisectAndDiffSegmentsEnhanced(table1, table2, infoTreeRecorder, level, Long.MAX_VALUE, adaptiveFactor, segmentId);
    }

    /**
     * Perform standard bisection.
     */
    private CompletableFuture<Void> performStandardBisection(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder,
            int level, long maxRows, String segmentId) {

        log.debug("Performing standard bisection for segment: {}", segmentId);

        // Standard split means real binary search, independent from the initial fan-out factor.
        infoTreeRecorder.recordSplit(segmentId, "standard", 2);
        infoTreeRecorder.markBisectSegment();
        return bisectAndDiffSegmentsEnhanced(table1, table2, infoTreeRecorder, level, maxRows, 2, segmentId);
    }

    /**
     * Calculate adaptive segmentation factor based on the larger table size.
     */
    private int calculateAdaptiveFactor(TableSegment table1, TableSegment table2) {
        long size1 = table1.approximateSize();
        long size2 = table2.approximateSize();
        
        // If approximateSize returns -1 (unknown), use actual count
        if (size1 < 0) {
            try {
                size1 = table1.count();
            } catch (Exception e) {
                log.warn("Failed to get count for table1 in calculateAdaptiveFactor", e);
                size1 = config.getBisectionThreshold() * 2;
            }
        }
        if (size2 < 0) {
            try {
                size2 = table2.count();
            } catch (Exception e) {
                log.warn("Failed to get count for table2 in calculateAdaptiveFactor", e);
                size2 = config.getBisectionThreshold() * 2;
            }
        }
        
        long maxSize = Math.max(size1, size2);
        int adaptiveFactor;

        // Adjust factor based on size
        if (maxSize > 1000000) {
            adaptiveFactor = Math.min(config.getBisectionFactor() * 2, 64);
            log.debug("Large dataset ({}), using adaptive factor: {} (base factor * 2)", maxSize, adaptiveFactor);
        } else if (maxSize > 100000) {
            adaptiveFactor = config.getBisectionFactor();
            log.debug("Medium dataset ({}), using adaptive factor: {} (base factor)", maxSize, adaptiveFactor);
        } else {
            adaptiveFactor = Math.max(config.getBisectionFactor() / 2, 4);
            log.debug("Small dataset ({}), using adaptive factor: {} (base factor / 2)", maxSize, adaptiveFactor);
        }
        
        return adaptiveFactor;
    }

    /**
     * Create initial bounds and start bisection.
     */
    private CompletableFuture<Void> createInitialBoundsAndBisection(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, int level, int bisectionFactor,
            String parentSegmentId) {

        return getInitialBounds(table1, table2)
                .thenCompose(bounds -> {
                    TableSegment bounded1 = createBoundedSegment(table1, bounds.table1Bounds);
                    TableSegment bounded2 = createBoundedSegment(table2, bounds.table2Bounds);

                    return bisectAndDiffSegmentsEnhanced(bounded1, bounded2, infoTreeRecorder, level, bounds.maxRows,
                            bisectionFactor, parentSegmentId);
                });
    }

    /**
     * Create corresponding segments for the smaller table and process all segment
     * pairs.
     */
    private CompletableFuture<Void> createCorrespondingSegmentsAndProcess(
            TableSegment smallerTable,
            List<TableSegment> largerSegments,
            TableSegment originalTable1,
            TableSegment originalTable2,
            TableSegment largerTable,
            InfoTreeRecorder infoTreeRecorder,
            int level,
            String parentSegmentId) {

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        log.debug("Creating corresponding segments: smallerTable={}, largerTable={}", 
                smallerTable.getTablePath(), largerTable.getTablePath());
        log.debug("  smallerTable database: {}", 
                smallerTable.getDatabase() != null ? smallerTable.getDatabase().getName() : "null");

        for (int segIdx = 0; segIdx < largerSegments.size(); segIdx++) {
            TableSegment largerSegment = largerSegments.get(segIdx);
            
            log.debug("Processing segment {}: largerSegment database={}", segIdx,
                    largerSegment.getDatabase() != null ? largerSegment.getDatabase().getName() : "null");

            // Create a new segment for the smaller table with the same min/max bounds as the larger segment.
            // This keeps both sides aligned even though only one side dictated the checkpoint placement.
            TableSegment correspondingSegment = smallerTable.toBuilder()
                    .minKey(largerSegment.getMinKey())
                    .maxKey(largerSegment.getMaxKey())
                    .build();

            log.debug("  correspondingSegment database after toBuilder: {}", 
                    correspondingSegment.getDatabase() != null ? correspondingSegment.getDatabase().getName() : "null");

            // Determine which segment is table1 and which is table2
            TableSegment t1, t2;
            if (largerTable == originalTable1) {
                t1 = largerSegment;
                t2 = correspondingSegment;
                log.debug("  Assignment: t1=largerSegment ({}), t2=correspondingSegment ({})", 
                        t1.getDatabase() != null ? t1.getDatabase().getName() : "null",
                        t2.getDatabase() != null ? t2.getDatabase().getName() : "null");
            } else {
                t1 = correspondingSegment;
                t2 = largerSegment;
                log.debug("  Assignment: t1=correspondingSegment ({}), t2=largerSegment ({})", 
                        t1.getDatabase() != null ? t1.getDatabase().getName() : "null",
                        t2.getDatabase() != null ? t2.getDatabase().getName() : "null");
            }

            long size1 = t1.approximateSize();
            long size2 = t2.approximateSize();
            
            // If approximateSize returns -1 (unknown), use actual count
            if (size1 < 0) {
                try {
                    size1 = t1.count();
                } catch (Exception e) {
                    size1 = config.getBisectionThreshold();
                }
            }
            if (size2 < 0) {
                try {
                    size2 = t2.count();
                } catch (Exception e) {
                    size2 = config.getBisectionThreshold();
                }
            }
            
            long segmentMaxRows = Math.max(size1, size2);

            // Each aligned pair gets diffed concurrently. The summed futures allow the caller to await completion.
            String permitRequestId = parentSegmentId + "#child-" + segIdx;
            CompletableFuture<Void> future = diffSegmentWithPermit(
                    permitRequestId, t1, t2, infoTreeRecorder, segmentMaxRows, level + 1, parentSegmentId);
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> diffSegmentWithPermit(
            String permitRequestId,
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level,
            String parentSegmentId) {

        return activeSegmentBudget.acquire(permitRequestId)
                .thenCompose(lease -> {
                    log.debug("Segment permit acquired for {} (availablePermits={}, queueDepth={})",
                            permitRequestId, activeSegmentBudget.availablePermits(), activeSegmentBudget.queueDepth());
                    try {
                        return diffSegmentsWithParent(table1, table2, infoTreeRecorder, maxRows, level, parentSegmentId)
                                .whenComplete((ignored, error) -> lease.release());
                    } catch (Throwable error) {
                        lease.release();
                        return CompletableFuture.failedFuture(error);
                    }
                });
    }

    /**
     * Store differences in the internal collection for final result.
     */
    private void storeDifferences(InfoTreeRecorder infoTreeRecorder, List<DiffRow> differences, String segmentId) {
        if (differences == null || differences.isEmpty()) {
            return;
        }
        ensureDiffLimit(differences.size());

        log.debug("Streaming {} differences for segment: {}", differences.size(), segmentId);
        infoTreeRecorder.addMetric(segmentId, "differences", differences.size());

        long sourceMissingDelta = 0;
        long targetMissingDelta = 0;
        long mismatchDelta = 0;
        for (DiffRow diff : differences) {
            if (diff == null) {
                continue;
            }
            switch (diff.getOperation()) {
                case SOURCE_MISSING:
                    sourceMissingCount.incrementAndGet();
                    sourceMissingDelta++;
                    break;
                case TARGET_MISSING:
                    targetMissingCount.incrementAndGet();
                    targetMissingDelta++;
                    break;
                case MISMATCH:
                    mismatchCount.incrementAndGet();
                    mismatchDelta++;
                    break;
                default:
                    break;
            }
        }

        infoTreeRecorder.addDiffCounts(segmentId, differences.size(), sourceMissingDelta, targetMissingDelta, mismatchDelta);
        emitDiffRows(differences);
    }

    /**
     * Create final diff result.
     */
    private DiffResult createDiffResult(TableSegment table1, TableSegment table2, Void result, long sourceRowCount,
            long targetRowCount, long durationMs) {
        long sourceMissing = sourceMissingCount.get();
        long targetMissing = targetMissingCount.get();
        long mismatched = mismatchCount.get();
        long totalDifferences = sourceMissing + targetMissing + mismatched;
        double differencePercentage = (sourceRowCount + targetRowCount > 0)
                ? (double) totalDifferences / Math.max(sourceRowCount, targetRowCount) * 100.0
                : 0.0;

        DiffResult.DiffStatistics statistics = DiffResult.DiffStatistics.builder()
                .sourceRowCount(sourceRowCount)
                .targetRowCount(targetRowCount)
                .sourceMissingCount(sourceMissing)
                .targetMissingCount(targetMissing)
                .mismatchCount(mismatched)
                .totalDifferences(totalDifferences)
                .processingTimeMs(durationMs)
                .unchangedCount(Math.max(0, Math.min(sourceRowCount, targetRowCount) - mismatched))
                .differencePercentage(differencePercentage)
                .build();

        completeDiff(statistics);

        return DiffResult.builder()
                .differences(getCollectedDifferences())
                .statistics(statistics)
                .sourceTablePath(table1.getTablePath())
                .targetTablePath(table2.getTablePath())
                .completedAt(Instant.now())
                .metadata(new HashMap<>())
                .build();
    }

    /**
     * Generate unique segment ID.
     */
    private String generateSegmentId(TableSegment table1, TableSegment table2, int level) {
        String base = String.format("L%d_%s_vs_%s", level,
                table1.getTablePath().toString().replace(".", "_"),
                table2.getTablePath().toString().replace(".", "_"));
        return base + "_" + buildSegmentKey(table1, table2);
    }

    private String buildSegmentKey(TableSegment table1, TableSegment table2) {
        String key1 = buildSegmentKey(table1);
        String key2 = buildSegmentKey(table2);
        if (key1 != null && !key1.isBlank()) {
            return key1;
        }
        if (key2 != null && !key2.isBlank()) {
            return key2;
        }
        return "seg" + segmentSequence.incrementAndGet();
    }

    private String buildSegmentKey(TableSegment table) {
        if (table == null) {
            return null;
        }
        String minKey = table.getMinKey().map(Object::toString).orElse(null);
        String maxKey = table.getMaxKey().map(Object::toString).orElse(null);
        String where = table.getWhereClause().orElse(null);
        String limitOffset = table.getLimitOffset()
                .map(lo -> lo.getOffset() + ":" + lo.getLimit())
                .orElse(null);
        int index = table.getSegmentIndex();
        boolean hasIdentity = minKey != null || maxKey != null || where != null || limitOffset != null || index != 0;
        if (!hasIdentity) {
            return "seg" + segmentSequence.incrementAndGet();
        }
        String raw = String.join("|",
                String.valueOf(minKey),
                String.valueOf(maxKey),
                String.valueOf(where),
                String.valueOf(limitOffset),
                String.valueOf(index));
        return "seg" + Integer.toHexString(Objects.hash(raw));
    }

    /**
     * Generate checksum cache key.
     */
    private String generateChecksumCacheKey(TableSegment segment) {
        // CRITICAL: Include database identifier AND table path to avoid cache collision
        // Bug fix: Previously missing table path caused different tables in same database
        // to share cache entries, leading to false negatives in diff detection
        String databaseId;
        if (segment.getDatabase() != null) {
            String jdbcUrl = segment.getDatabase().getConnectionPool().getConfiguration().getJdbcUrl();
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(jdbcUrl.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(8, digest.length); i++) {
                    sb.append(String.format("%02x", digest[i]));
                }
                databaseId = sb.toString();
            } catch (NoSuchAlgorithmException e) {
                // Fallback to database name if MD5 is not available
                databaseId = segment.getDatabase().getName();
            }
        } else {
            databaseId = "unknown";
        }
        
        // Include full table path to ensure different tables have different cache keys
        String tablePath = segment.getTablePath() != null ? segment.getTablePath().toString() : "null";
        
        return String.format("%s_%s_%s_%s_%s",
                databaseId,
                tablePath,
                segment.getMinKey().map(Object::toString).orElse("null"),
                segment.getMaxKey().map(Object::toString).orElse("null"),
                segment.getWhereClause().orElse("null"));
    }

    @Override
    public void close() {
        try {
            performanceMonitor.clear();
            checksumCache.clear();
            progressReporter.shutdown();
            super.shutdown();
            log.info("ChecksumDiffer closed successfully");
        } catch (Exception e) {
            log.error("Error closing ChecksumDiffer", e);
        }
    }

    /**
     * Initial bounds information.
     */
    private static class InitialBounds {
        final ChecksumResult table1Bounds;
        final ChecksumResult table2Bounds;
        final long maxRows;

        InitialBounds(ChecksumResult table1Bounds, ChecksumResult table2Bounds, long maxRows) {
            this.table1Bounds = table1Bounds;
            this.table2Bounds = table2Bounds;
            this.maxRows = maxRows;
        }
    }

    /**
     * High-performance checksum cache backed by Caffeine.
     */
    private static class ChecksumCache {
        private static final int MAX_SIZE = 1000;
        private final Cache<String, ChecksumResult> cache;

        public ChecksumCache() {
            this.cache = Caffeine.newBuilder()
                    .maximumSize(MAX_SIZE)
                    .build();
        }

        public ChecksumResult get(String key) {
            return cache.getIfPresent(key);
        }

        public void put(String key, ChecksumResult value) {
            cache.put(key, value);
        }

        public void clear() {
            cache.invalidateAll();
        }
    }

    private static class PerformanceMonitor {
        private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
        private final Map<String, Object> metrics = new ConcurrentHashMap<>();
        private final AtomicLong segmentCount = new AtomicLong(0);

        public void startDiff(String segmentId) {
            startTimes.put(segmentId, System.currentTimeMillis());
            segmentCount.incrementAndGet();
        }

        public void endDiff(String segmentId) {
            Long startTime = startTimes.remove(segmentId);
            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                metrics.put(segmentId + "_duration", duration);
            }
        }

        public void recordChecksum(String segmentId, ChecksumResult result1, ChecksumResult result2) {
            metrics.put(segmentId + "_rows1", result1.getCount());
            metrics.put(segmentId + "_rows2", result2.getCount());
            metrics.put(segmentId + "_checksum_match",
                    Objects.equals(result1.getChecksum(), result2.getChecksum()));
        }

        public void recordLocalComparison(String segmentId, int rows1, int rows2, int differences) {
            metrics.put(segmentId + "_local_comparison", true);
            metrics.put(segmentId + "_local_differences", differences);
        }

        public void recordStage(String stage) {
            metrics.put(stage, System.currentTimeMillis());
        }

        public Map<String, Object> getMetrics() {
            return new HashMap<>(metrics);
        }

        public long getSegmentCount() {
            return segmentCount.get();
        }

        public void clear() {
            startTimes.clear();
            metrics.clear();
            segmentCount.set(0);
        }
    }

    private static class ProgressReporter {
        private volatile long totalSegments = 0;
        private volatile long completedSegments = 0;
        private volatile long startTime = 0;
        private final AtomicLong processedRows = new AtomicLong(0);
        private final AtomicLong foundDifferences = new AtomicLong(0);

        public void initialize(TableSegment table1, TableSegment table2, long bisectionThreshold) {
            this.startTime = System.currentTimeMillis();
            this.completedSegments = 0;
            this.processedRows.set(0);
            this.foundDifferences.set(0);

            // Get approximate sizes
            long table1Size = table1.approximateSize();
            long table2Size = table2.approximateSize();

            // Handle unbounded segments (Long.MAX_VALUE) or unknown sizes (-1)
            if (table1Size == Long.MAX_VALUE || table1Size < 0) {
                try {
                    table1Size = table1.count();
                    log.debug("Using actual count for table1: {}", table1Size);
                } catch (Exception e) {
                    log.warn("Failed to get actual count for table1, using default", e);
                    table1Size = bisectionThreshold * 10; // Conservative estimate
                }
            }

            if (table2Size == Long.MAX_VALUE || table2Size < 0) {
                try {
                    table2Size = table2.count();
                    log.debug("Using actual count for table2: {}", table2Size);
                } catch (Exception e) {
                    log.warn("Failed to get actual count for table2, using default", e);
                    table2Size = bisectionThreshold * 10; // Conservative estimate
                }
            }

            long maxSize = Math.max(table1Size, table2Size);

            // Estimate: for every bisectionThreshold rows, we might have 1 segment
            // This gives a better estimate based on actual configuration
            this.totalSegments = Math.max(1, (maxSize / bisectionThreshold) + 1);

            log.info("Progress initialized - Estimated segments: {}, Table sizes: {} vs {}, Bisection threshold: {}",
                    totalSegments, table1Size, table2Size, bisectionThreshold);
        }

        public void segmentCompleted(String segmentId, long rowsProcessed, long differencesFound) {
            completedSegments++;
            processedRows.addAndGet(rowsProcessed);
            foundDifferences.addAndGet(differencesFound);

            if (completedSegments % 10 == 0 || completedSegments == totalSegments) {
                reportProgress();
            }
        }

        public void reportProgress() {
            double progressPercentage = totalSegments > 0 ? (double) completedSegments / totalSegments * 100.0 : 0.0;

            long elapsed = System.currentTimeMillis() - startTime;
            double elapsedSeconds = elapsed / 1000.0;

            long estimatedTotalTime = progressPercentage > 0 ? (long) (elapsed * 100.0 / progressPercentage) : 0;
            long remainingTime = Math.max(0, estimatedTotalTime - elapsed);

            log.info(
                    "Progress: {}/{} segments ({}%) | Rows processed: {} | Differences found: {} | Elapsed: {}s | ETA: {}s",
                    completedSegments, totalSegments,
                    String.format("%.1f", progressPercentage),
                    processedRows.get(), foundDifferences.get(),
                    String.format("%.1f", elapsedSeconds),
                    String.format("%.1f", remainingTime / 1000.0));
        }

        public void complete() {
            long totalTime = System.currentTimeMillis() - startTime;
            log.info(
                    "Diff operation completed - Total segments: {}, Rows processed: {}, Differences found: {}, Total time: {}ms",
                    completedSegments, processedRows.get(), foundDifferences.get(), totalTime);
        }

        public void shutdown() {
            log.info("Progress reporter shutdown");
        }
    }
}
