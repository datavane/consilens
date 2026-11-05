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
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TableSchema;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
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

    /** 校验和缓存键版本：行哈希/键编码规则变化时必须递增，避免复用旧版本的缓存结果。 */
    private static final String CHECKSUM_CACHE_VERSION = "h2";

    /** 已上报过的 (操作, 主键) 身份串，用于跨"主扫描/尾部补扫"去重。 */
    private final Set<String> reportedRowKeys = ConcurrentHashMap.newKeySet();
    /** 已上报过的 (操作, 主键)（不含 occurrence），尾扫按"整键已报"跳过，避免重复行。 */
    private final Set<String> reportedKeyOps = ConcurrentHashMap.newKeySet();


    private final ChecksumCache checksumCache;
    private final PerformanceMonitor performanceMonitor;
    private final AtomicLong sourceMissingCount = new AtomicLong(0);
    // 初始 bounds（尾扫用）：每个 diff 任务新建 ChecksumDiffer 实例，写入后只读
    private ChecksumResult initialBounds1;
    private ChecksumResult initialBounds2;
    private InfoTreeRecorder tailInfoRecorder;
    private final AtomicLong targetMissingCount = new AtomicLong(0);
    private final AtomicLong mismatchCount = new AtomicLong(0);
    /** 容差内行数（默认口径不计入 totalDifferences，只在报告里单列）。 */
    private final AtomicLong withinToleranceCount = new AtomicLong(0);
    /** 容差内行样本（受 withinToleranceSamples 上限约束；跨分段串行写入）。 */
    private final List<DiffRow> withinToleranceRows = java.util.Collections.synchronizedList(new ArrayList<>());
    /** 容差内行的去重身份（与差异行分开，互不吞并）。 */
    private final Set<String> reportedWithinToleranceKeys = ConcurrentHashMap.newKeySet();
    private final ProgressReporter progressReporter;
    private final AtomicLong segmentSequence = new AtomicLong(0);
    private final Map<DatabaseAdapter, TableSegmenter> segmenterCache = new ConcurrentHashMap<>();
    private static final int MAX_DEPTH_HEADROOM = 8;

    private final SegmentPermitController activeSegmentBudget;

    /** 预算守卫：maxDuration/maxRows/maxBytes 在取数点累计，超限抛 BudgetExceededException。 */
    private final BudgetGuard budgetGuard;

    /**
     * 按主键回查差异行时每批绑定的主键个数。单条 SQL 的 IN 列表过长会触及部分数据库的
     * 表达式/参数上限（Oracle 1000 个表达式）并放大解析开销；500 留出一半余量，
     * 同时避免批次过小导致往返次数显著放大。
     */
    private static final int KEY_QUERY_BATCH_SIZE = 500;

    /**
     * 尾扫取数失败后的最大尝试次数。瞬时失败（如 ClickHouse QUERY_WAS_CANCELLED、
     * 查询超时）通常在下一次重试时恢复；3 次足以覆盖单次抖动，又不会让暂时不可用的
     * 对端把失败路径拖得过长。
     */
    private static final int TAIL_SCAN_MAX_ATTEMPTS = 3;

    /**
     * 尾扫重试的线性退避基数（毫秒）：第 attempt 次失败后等待 base * attempt
     * （500ms、1000ms），3 次上限内总等待 1.5s，足以等待瞬时取消/超时恢复。
     */
    private static final long TAIL_RETRY_BASE_DELAY_MS = 500L;

    /**
     * 自适应分段的"大数据量"阈值：超过 100 万行时把初始扇出加倍（上限 64），
     * 让超大表的顶层分段更快收敛、减少后续二分轮数。
     */
    private static final long LARGE_TABLE_ROW_THRESHOLD = 1_000_000L;

    /**
     * 自适应分段的"中等数据量"阈值：10 万~100 万行沿用配置扇出；低于 10 万行时
     * 扇出减半（下限 4），避免小表被切得过碎、增加空段与往返开销。
     */
    private static final long MEDIUM_TABLE_ROW_THRESHOLD = 100_000L;

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
        this.budgetGuard = new BudgetGuard(config.getMaxDurationMs(), config.getMaxRowsScanned(),
                config.getMaxBytesFetched());
        int segmentBudget = resolveSegmentBudget(config);
        this.activeSegmentBudget = new SegmentPermitController(segmentBudget);
        
        log.info("ChecksumDiffer initialized with checksumAlgorithm: {}, local comparison mode: {}, segmentBudget={}",
                config.getChecksumAlgorithm(), config.getLocalCompareMode(), segmentBudget);
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
        return getInitialBounds(table1, table2, infoTreeRecorder)
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
                    // 预算拒绝不是执行失败：必须原样上抛，否则上层会按"可重试的 RUN_EXECUTION_ERROR"
                    // 处理，把"结果不完整"当成可以重跑就能好的问题
                    com.consilens.core.exception.BudgetExceededException budget = unwrapBudget(e);
                    if (budget != null) {
                        throw budget;
                    }
                    log.error("ChecksumDiff failed", e);
                    throw new RuntimeException("ChecksumDiff failed", e);
                });
    }

    /** 沿 cause 链找预算拒绝，找不到返回 null。 */
    private static com.consilens.core.exception.BudgetExceededException unwrapBudget(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof com.consilens.core.exception.BudgetExceededException) {
                return (com.consilens.core.exception.BudgetExceededException) current;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** 去掉区间边界的段：整表按归一化键比较时不能带 {@code key >= min} 之类的谓词。 */
    private static TableSegment unbounded(TableSegment segment) {
        if (segment == null) {
            return null;
        }
        return segment.toBuilder()
                .minKey(java.util.Optional.empty())
                .maxKey(java.util.Optional.empty())
                .build();
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
        return diffSegmentsWithParent(table1, table2, infoTreeRecorder, maxRows, level, parentSegmentId, null);
    }

    /**
     * @param releasePermit 在“继续下钻/本地比对”之前释放本段占用的并发许可。许可若一直持有到
     *                      子段完成，预算为 N 而同时有 N 个内部分段在等子段时，子段永远拿不到许可
     *                      （FIFO 队列被父段占满）——这是 P2-4 的根因。许可只需要覆盖本段的
     *                      checksum 查询，分段决策一旦做出就应归还。
     */
    private CompletableFuture<Void> diffSegmentsWithParent(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level,
            String parentSegmentId,
            Runnable releasePermit) {

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

        return diffSegmentsWithChecksum(table1, table2, infoTreeRecorder, maxRows, level, segmentId, releasePermit)
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
            String segmentId,
            Runnable releasePermit) {

        // Step 1: Calculate checksums for both segments (with caching)
        CompletableFuture<ChecksumResult> checksum1Future = getChecksumWithCache(table1, infoTreeRecorder, segmentId);
        CompletableFuture<ChecksumResult> checksum2Future = getChecksumWithCache(table2, infoTreeRecorder, segmentId);

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
                        releaseQuietly(releasePermit);
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
            boolean depthCapped = level > config.getMaxDepth() + MAX_DEPTH_HEADROOM;
            return performLocalComparison(table1, table2, infoTreeRecorder, segmentId, depthCapped);
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

        // Explicit recursion depth guard. maxDepth 是"软上限"：再给 8 层二分（行数最多再降 256 倍），
        // 之后才整段本地比较。原来的硬上限会在一个仍然很大的分段上直接把所有行拉进内存（P2-3），
        // 而再多切几层只是多几次 SQL，代价远小于 OOM；8 层的硬上限保证递归必然终止。
        int softDepthLimit = config.getMaxDepth() + MAX_DEPTH_HEADROOM;
        if (level > softDepthLimit) {
            log.warn("Recursion depth {} exceeds soft limit {} (maxDepth={}), forcing local comparison for segment {}",
                    level, softDepthLimit, config.getMaxDepth(), parentSegmentId);
            return performLocalComparison(table1, table2, infoTreeRecorder, parentSegmentId, true);
        }

        // allowNullableKeys 打开时键列可能混有 NULL：MIN()/MAX() 跳过 NULL，区间谓词对 NULL
        // 恒为 UNKNOWN，这些行不属于任何分段，会被静默漏报（统计行数却包含它们）。
        // 这种形态在根段直接做整表归一化键比较，NULL 键按归一化语义正常参与比较。
        if (level == 0 && config.isAllowNullableKeys()
                && (TableDiffer.mayContainNullKeys(table1) || TableDiffer.mayContainNullKeys(table2))) {
            log.warn("Nullable key column(s) with allowNullableKeys=true on {} / {}; comparing whole tables "
                            + "by normalized keys because range predicates cannot cover NULL keys",
                    table1.getDisplayName(), table2.getDisplayName());
            // 必须去掉边界：根段是 createBoundedSegment 的产物，带边界时 where 子句里仍有
            // "key >= min"，NULL 行照样取不出来，等于这条兜底没生效
            return performLocalComparison(unbounded(table1), unbounded(table2),
                    infoTreeRecorder, parentSegmentId, false);
        }

        // 键列排序语义不一致（如一侧 INT、另一侧 VARCHAR）时，任何区间谓词在两侧的含义都不同：
        // 分段边界不能跨侧复用，"> 对端 maxKey" 式尾扫也会把大量行误判为单侧缺失。此类形态直接
        // 整表按归一化键做哈希比较。判断必须放在"是否已有边界"之前——两侧都已 bounded 时
        // （根段由 createBoundedSegment 建好后即带边界，是漂移场景的常见入口）原来会跳过它。
        if (!keyOrderingCompatible(table1, table2)) {
            log.warn("Key ordering differs between {} and {}; comparing whole tables by normalized keys "
                    + "instead of range predicates", table1.getDisplayName(), table2.getDisplayName());
            return performLocalComparison(table1, table2, infoTreeRecorder, parentSegmentId, false);
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
        // 分段基座取两侧键范围的并集：只按较大表的键空间分段时，较小表中落在该空间之外的行
        // 不属于任何分段（分段边界来自较大表，较小表的越界行永远不会被访问到），此前只能靠
        // 事后的尾扫补报。以并集为基座后，这些行落入"一侧有行、另一侧无行"的段，由常规段
        // 比较直接给出单侧缺失；尾扫（等值极值探测）退化为排序规则差异的兜底。
        TableSegment segmentationBase = withUnionKeySpace(largerTable, table1, table2);

        log.debug("Larger table: {}, Smaller table: {}",
                largerTable.getTablePath(), smallerTable.getTablePath());

        // Create a dedicated segmenter for the larger table to avoid database adapter confusion
        TableSegmenter largerTableSegmenter = getOrCreateSegmenter(largerTable);

        return largerTableSegmenter.createOptimalSegments(segmentationBase, bisectionFactor, config.getBisectionThreshold())
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
    private CompletableFuture<InitialBounds> getInitialBounds(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder) {
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
                infoTreeRecorder.addQueryCount("checksum-diff", 2);

                long maxRows = Math.max(bounds1.getCount(), bounds2.getCount());

                log.info("Initial bounds established - Table1: count={}, minKey={}, maxKey={}", 
                        bounds1.getCount(), bounds1.getMinKey(), bounds1.getMaxKey());
                log.info("Initial bounds established - Table2: count={}, minKey={}, maxKey={}", 
                        bounds2.getCount(), bounds2.getMinKey(), bounds2.getMaxKey());

                initialBounds1 = bounds1;
                initialBounds2 = bounds2;
                tailInfoRecorder = infoTreeRecorder;
                return new InitialBounds(bounds1, bounds2, maxRows);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get initial bounds", e);
            }
        }, executorProvider.getIoExecutor());
    }

    /**
     * Get checksum with caching support.
     */
    private CompletableFuture<ChecksumResult> getChecksumWithCache(
            TableSegment segment, InfoTreeRecorder infoTreeRecorder, String segmentId) {
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
            infoTreeRecorder.addQueryCount(segmentId, 1);
            checksumCache.put(cacheKey, result);
            return result;
        }, executorProvider.getIoExecutor());
    }

    /**
     * Create bounded segment from checksum result.
     * 
     * CRITICAL: Initial bounds come from actual observed boundary rows, so the root segment
     * must include the upper bound. Recursive child segments still use exclusive upper bounds
     * to avoid overlap; only this root bounded segment flips to inclusive.
     */
    private TableSegment createBoundedSegment(TableSegment original, ChecksumResult bounds) {
        if (bounds == null || bounds.getMinKey() == null || bounds.getMaxKey() == null) {
            return original;
        }

        return original.toBuilder()
                .minKey(Optional.of(bounds.getMinKey()))
                .maxKey(Optional.of(bounds.getMaxKey()))
                .upperBoundInclusive(true)
                .build();
    }

    /**
     * 把分段基座的键范围扩大到两侧的并集，保留该表的连接与过滤条件。
     * 一侧探测不到范围（空表）时沿用另一侧；两侧都没有时保持原样。
     */
    private TableSegment withUnionKeySpace(TableSegment segment, TableSegment table1, TableSegment table2) {
        Optional<List<Object>> min = unionBound(table1.getMinKey(), table2.getMinKey(), true);
        Optional<List<Object>> max = unionBound(table1.getMaxKey(), table2.getMaxKey(), false);
        if (min.isEmpty() && max.isEmpty()) {
            return segment;
        }
        TableSegment.TableSegmentBuilder builder = segment.toBuilder();
        min.ifPresent(bounds -> builder.minKey(Optional.of(bounds)));
        max.ifPresent(bounds -> builder.maxKey(Optional.of(bounds)));
        return builder.build();
    }

    /**
     * 两侧边界取并集意义上的"外侧"：下界取更小者，上界取更大者。
     * 任一侧没有可用边界时直接取另一侧（空表侧不贡献范围）。
     */
    private Optional<List<Object>> unionBound(Optional<List<Object>> left, Optional<List<Object>> right,
                                             boolean takeMin) {
        Optional<List<Object>> leftBound = usable(left);
        Optional<List<Object>> rightBound = usable(right);
        if (leftBound.isEmpty()) {
            return rightBound;
        }
        if (rightBound.isEmpty()) {
            return leftBound;
        }
        int comparison = compareKeys(leftBound.get(), rightBound.get());
        return (takeMin ? comparison <= 0 : comparison >= 0) ? leftBound : rightBound;
    }

    private Optional<List<Object>> usable(Optional<List<Object>> bounds) {
        if (bounds == null || bounds.isEmpty() || bounds.get() == null || bounds.get().isEmpty()) {
            return Optional.empty();
        }
        return bounds;
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
        // - level > maxDepth + headroom: 硬兜底，防止病态键分布下无限细分。
        return totalRows < config.getBisectionThreshold()
                || totalRows < config.getBisectionFactor() * 2L
                || level > config.getMaxDepth() + MAX_DEPTH_HEADROOM;
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
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder,
            String segmentId, boolean depthCapped) {

        LocalCompareMode localCompareMode = config.getLocalCompareMode();
        log.info("Performing local comparison for segment: {} using mode: {}", segmentId, localCompareMode);
        infoTreeRecorder.recordMetric(segmentId, "localCompareMode", localCompareMode.getCode());

        if (localCompareMode.isRowHash()) {
            return performRowHashBasedComparison(table1, table2, infoTreeRecorder, segmentId);
        }
        if (depthCapped) {
            // FULL 模式在深度上限仍然要拉全列，内存风险最高；这里改用行哈希兜底，
            // 仍然用 LocalDiffEngine 精确复核候选键，结果口径不变。
            infoTreeRecorder.recordMetric(segmentId, "depthCappedLocalCompare", "row_hash_fallback");
            return performDepthCappedComparison(table1, table2, infoTreeRecorder, segmentId);
        }
        return performFullDataComparison(table1, table2, infoTreeRecorder, segmentId);
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
    private CompletableFuture<RowChecksumDiffData> computeRowHashDiffData(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Using row-hash based comparison for segment: {}", segmentId);
            Map<String, DataType> columnTypes1 = extractColumnTypes(table1);
            Map<String, DataType> columnTypes2 = extractColumnTypes(table2);

            // Only one side is fully indexed in memory; the other side is streamed and
            // matched incrementally so large segments do not hold both hash maps at once.
            RowHashSide index1 = new RowHashSide();
            boolean[] duplicates = new boolean[1];
            table1.getDatabase().forEachSegmentRowHash(table1, (rawKey, hash) -> {
                long keyBytes = estimateKeyHashBytes(rawKey, hash);
                infoTreeRecorder.addBytesFetched(segmentId, keyBytes);
                budgetGuard.addBytesFetched(keyBytes);
                List<Object> normalizedKey = normalizeKeyValues(rawKey, table1.getKeyColumns(), columnTypes1);
                if (index1.put(normalizedKey, rawKey, hash)) {
                    duplicates[0] = true;
                }
            });

            Set<List<Object>> keysOnlyInTable2 = new HashSet<>();
            Map<List<Object>, List<Object>> rawKeysOnlyInTable2 = new HashMap<>();
            Set<List<Object>> mismatchedKeys = new HashSet<>();
            Map<List<Object>, List<Object>> rawKeysMismatchedIn1 = new HashMap<>();
            Map<List<Object>, List<Object>> rawKeysMismatchedIn2 = new HashMap<>();
            Map<List<Object>, String> mismatchHashesIn1 = new HashMap<>();
            Map<List<Object>, String> mismatchHashesIn2 = new HashMap<>();

            long[] table2RowCount = new long[1];
            Set<List<Object>> seenInTable2 = new HashSet<>();
            table2.getDatabase().forEachSegmentRowHash(table2, (rawKey2, hash2) -> {
                long keyBytes2 = estimateKeyHashBytes(rawKey2, hash2);
                infoTreeRecorder.addBytesFetched(segmentId, keyBytes2);
                budgetGuard.addBytesFetched(keyBytes2);
                table2RowCount[0]++;
                List<Object> normalizedKey = normalizeKeyValues(rawKey2, table2.getKeyColumns(), columnTypes2);
                if (!seenInTable2.add(normalizedKey)) {
                    duplicates[0] = true;
                }
                RowHashEntry entry1 = index1.remove(normalizedKey);
                if (entry1 == null) {
                    infoTreeRecorder.markFirstDifference();
                    keysOnlyInTable2.add(normalizedKey);
                    rawKeysOnlyInTable2.put(normalizedKey, rawKey2);
                } else if (!entry1.hash.equals(hash2)) {
                    infoTreeRecorder.markFirstDifference();
                    mismatchedKeys.add(normalizedKey);
                    rawKeysMismatchedIn1.put(normalizedKey, entry1.rawKey);
                    rawKeysMismatchedIn2.put(normalizedKey, rawKey2);
                    mismatchHashesIn1.put(normalizedKey, entry1.hash);
                    mismatchHashesIn2.put(normalizedKey, hash2);
                }
            });
            infoTreeRecorder.addQueryCount(segmentId, 2);

            Map<List<Object>, List<Object>> rawKeysOnlyInTable1 = index1.remainingRawKeys();
            Set<List<Object>> keysOnlyInTable1 = new HashSet<>(rawKeysOnlyInTable1.keySet());
            if (!keysOnlyInTable1.isEmpty()) {
                infoTreeRecorder.markFirstDifference();
            }

            int totalDifferences = keysOnlyInTable1.size() + keysOnlyInTable2.size() + mismatchedKeys.size();
            log.info("Hash comparison found {} differences: {} only in table1, {} only in table2, {} mismatched",
                    totalDifferences, keysOnlyInTable1.size(), keysOnlyInTable2.size(), mismatchedKeys.size());

            // Log first few mismatched keys for diagnosis
            if (!mismatchedKeys.isEmpty()) {
                int debugCount = 0;
                for (List<Object> key : mismatchedKeys) {
                    if (debugCount++ < 5) {
                        log.debug("Hash mismatch for key {}: source_hash={}, target_hash={}",
                                key, mismatchHashesIn1.get(key), mismatchHashesIn2.get(key));
                    } else {
                        break;
                    }
                }
            }

            return new RowHashSnapshot(keysOnlyInTable1, rawKeysOnlyInTable1,
                    keysOnlyInTable2, rawKeysOnlyInTable2,
                    mismatchedKeys, rawKeysMismatchedIn1, rawKeysMismatchedIn2,
                    index1.total(), table2RowCount[0], columnTypes1, columnTypes2, duplicates[0]);
        }, executorProvider.getIoExecutor())
                .thenComposeAsync(snapshot -> {
                    if (snapshot.duplicateKeys) {
                        // 重复键交由调用方走全量多重集比较（emit 阶段决定），这里不再嵌套比较，
                        // 以便"深度上限兜底"能在不重复产出的前提下安全回退。
                        return CompletableFuture.completedFuture(new RowChecksumDiffData(
                                snapshot,
                                Collections.emptyMap(),
                                Collections.emptyMap(),
                                snapshot.columnTypes1,
                                snapshot.columnTypes2));
                    }
                    if (snapshot.totalDifferences() == 0) {
                        return CompletableFuture.completedFuture(new RowChecksumDiffData(
                                snapshot,
                                Collections.emptyMap(),
                                Collections.emptyMap(),
                                snapshot.columnTypes1,
                                snapshot.columnTypes2));
                    }
                    Set<List<Object>> rawKeys1 = new LinkedHashSet<>(snapshot.rawKeysOnlyInTable1.values());
                    rawKeys1.addAll(snapshot.rawKeysMismatchedIn1.values());
                    Set<List<Object>> rawKeys2 = new LinkedHashSet<>(snapshot.rawKeysOnlyInTable2.values());
                    rawKeys2.addAll(snapshot.rawKeysMismatchedIn2.values());

                    Map<List<Object>, Object[]> data1 = queryRowsByKeys(
                            table1, rawKeys1, snapshot.columnTypes1, infoTreeRecorder, segmentId);
                    Map<List<Object>, Object[]> data2 = queryRowsByKeys(
                            table2, rawKeys2, snapshot.columnTypes2, infoTreeRecorder, segmentId);
                    return CompletableFuture.completedFuture(new RowChecksumDiffData(snapshot, data1, data2,
                            snapshot.columnTypes1, snapshot.columnTypes2));
                }, executorProvider.getIoExecutor());
    }

    /** 行哈希方案（候选键筛选 + 精确复核），失败时按调用方要求决定是否回退。 */
    private CompletableFuture<Void> performRowHashBasedComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {
        return compareUsingRowHash(table1, table2, infoTreeRecorder, segmentId, false);
    }

    /**
     * 深度上限（软上限也已用尽）时的兜底路径：优先用行哈希把内存占用从"整段全列"降到
     * "单侧键 + 哈希"，失败再退回全量本地比较——此时行哈希尚未产出任何差异，
     * 回退不会重复计数（storeDifferences 在 emit 阶段才发生）。
     */
    private CompletableFuture<Void> performDepthCappedComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {
        return compareUsingRowHash(table1, table2, infoTreeRecorder, segmentId, true);
    }

    private CompletableFuture<Void> compareUsingRowHash(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder,
            String segmentId, boolean fallBackOnFailure) {
        return computeRowHashDiffData(table1, table2, infoTreeRecorder, segmentId)
                .handle((data, error) -> {
                    if (error == null) {
                        return data;
                    }
                    if (!fallBackOnFailure) {
                        throw new java.util.concurrent.CompletionException(error);
                    }
                    log.warn("Row-hash comparison failed for segment {}; falling back to full local compare",
                            segmentId, error);
                    return null;
                })
                .thenComposeAsync(data -> {
                    if (data == null || data.plan.duplicateKeys) {
                        if (data != null) {
                            log.info("Row-hash comparison [{}] found duplicate keys; "
                                    + "falling back to full data compare", segmentId);
                        }
                        return performFullDataComparison(table1, table2, infoTreeRecorder, segmentId);
                    }
                    emitRowHashDifferences(data, table1, table2, infoTreeRecorder, segmentId);
                    return CompletableFuture.completedFuture(null);
                }, executorProvider.getCpuExecutor());
    }

    private void emitRowHashDifferences(RowChecksumDiffData diffData, TableSegment table1, TableSegment table2,
            InfoTreeRecorder infoTreeRecorder, String segmentId) {
            try {
                RowHashSnapshot snapshot = diffData.plan;
                List<DiffRow> differences = new ArrayList<>();
                List<DiffRow> withinTolerance = new ArrayList<>();

                if (snapshot.totalDifferences() > 0) {
                    // 行哈希只是"候选"筛选：不同方言/格式下同一行可能哈希不同（反之
                    // 哈希相同也可能因分隔符碰撞而误判相等）。对候选键取回完整行后，
                    // 必须用与全量比较相同的 LocalDiffEngine 做精确判定，否则会把
                    // "哈希不同但值相同"的行误报为 MISMATCH，也会丢掉重复键的多重集语义。
                    DataTypeHandler handler1 = dataTypeHandler(table1);
                    DataTypeHandler handler2 = dataTypeHandler(table2);
                    for (List<Object> key : snapshot.keysOnlyInTable1) {
                        Object[] row = diffData.data1.get(key);
                        if (row != null) {
                            differences.addAll(compareKeyRows(
                                    Collections.singletonList(row), Collections.emptyList(),
                                    table1, table2, diffData.columnTypes1, diffData.columnTypes2,
                                    handler1, handler2, withinTolerance));
                        }
                    }

                    for (List<Object> key : snapshot.keysOnlyInTable2) {
                        Object[] row = diffData.data2.get(key);
                        if (row != null) {
                            differences.addAll(compareKeyRows(
                                    Collections.emptyList(), Collections.singletonList(row),
                                    table1, table2, diffData.columnTypes1, diffData.columnTypes2,
                                    handler1, handler2, withinTolerance));
                        }
                    }

                    for (List<Object> key : snapshot.mismatchedKeys) {
                        Object[] row1 = diffData.data1.get(key);
                        Object[] row2 = diffData.data2.get(key);
                        if (row1 != null && row2 != null) {
                            differences.addAll(compareKeyRows(
                                    Collections.singletonList(row1), Collections.singletonList(row2),
                                    table1, table2, diffData.columnTypes1, diffData.columnTypes2,
                                    handler1, handler2, withinTolerance));
                        }
                    }
                }

                performanceMonitor.recordLocalComparison(segmentId,
                        (int) Math.min(snapshot.table1RowCount, Integer.MAX_VALUE),
                        (int) Math.min(snapshot.table2RowCount, Integer.MAX_VALUE),
                        differences.size());

                storeWithinToleranceRows(infoTreeRecorder, differences, withinTolerance, segmentId);
                storeDifferences(infoTreeRecorder, differences, segmentId);
                infoTreeRecorder.addRowsFetched(segmentId,
                        snapshot.table1RowCount + snapshot.table2RowCount);
                budgetGuard.addRowsScanned(snapshot.table1RowCount + snapshot.table2RowCount);

                progressReporter.segmentCompleted(segmentId,
                        snapshot.table1RowCount + snapshot.table2RowCount,
                        differences.size());

                log.info("Row-hash based comparison completed: {} differences for segment: {}",
                        differences.size(), segmentId);
            } catch (Exception e) {
                log.error("Row-hash based comparison failed for segment: {}", segmentId, e);
                throw new RuntimeException("Row-hash based comparison failed", e);
            }
    }

    /**
     * Perform full data comparison.
     */
    /**
     * 本地比较使用的值归一化器：由方言决定驱动特定类型（如 MySQL YEAR）的比较形态。
     */
    private static DataTypeHandler dataTypeHandler(TableSegment segment) {
        if (segment == null || segment.getDatabase() == null || segment.getDatabase().getDialect() == null) {
            return null;
        }
        return segment.getDatabase().getDialect().getDataTypeHandler();
    }

    /**
     * 对候选键的两侧行做精确比较（与全量比较同一套归一化/多重集语义）。
     * 归一化 spec 透传给 LocalDiffEngine，用于解析列级/类型级数值容差。
     */
    private List<DiffRow> compareKeyRows(List<Object[]> rows1, List<Object[]> rows2,
            TableSegment table1, TableSegment table2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2,
            DataTypeHandler handler1, DataTypeHandler handler2,
            List<DiffRow> withinTolerance) {
        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                rows1, rows2,
                table1.getKeyColumns(), table1.getExtraColumns(),
                table2.getKeyColumns(), table2.getExtraColumns(),
                columnTypes1, columnTypes2,
                handler1, handler2,
                config.getNormalizationSpec(),
                true);
        if (withinTolerance != null) {
            withinTolerance.addAll(outcome.getWithinTolerance());
        }
        return outcome.getDifferences();
    }

    private CompletableFuture<Void> performFullDataComparison(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, String segmentId) {

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Using full data comparison for segment: {}", segmentId);
            List<Object[]> rows1 = table1.getValues();
            List<Object[]> rows2 = table2.getValues();
            infoTreeRecorder.addQueryCount(segmentId, 2);
            long fullBytes = estimateRowsBytes(rows1) + estimateRowsBytes(rows2);
            infoTreeRecorder.addBytesFetched(segmentId, fullBytes);
            budgetGuard.addBytesFetched(fullBytes);
            return new FullDataSnapshot(rows1, rows2);
        }, executorProvider.getIoExecutor())
                .thenAcceptAsync(snapshot -> {
                    try {
                        Map<String, DataType> columnTypes1 = extractColumnTypes(table1);
                        Map<String, DataType> columnTypes2 = extractColumnTypes(table2);

                        LocalDiffEngine.ToleranceOutcome outcome = LocalDiffEngine.findDifferencesWithToleranceOutcome(
                                snapshot.rows1, snapshot.rows2,
                                table1.getKeyColumns(),
                                table1.getExtraColumns(),
                                table2.getKeyColumns(),
                                table2.getExtraColumns(),
                                columnTypes1,
                                columnTypes2,
                                dataTypeHandler(table1),
                                dataTypeHandler(table2),
                                config.getNormalizationSpec(),
                                true);
                        List<DiffRow> differences = outcome.getDifferences();

                        performanceMonitor.recordLocalComparison(segmentId,
                                snapshot.rows1.size(), snapshot.rows2.size(), differences.size());

                        storeWithinToleranceRows(infoTreeRecorder, differences, outcome.getWithinTolerance(), segmentId);
                        storeDifferences(infoTreeRecorder, differences, segmentId);
                        infoTreeRecorder.addRowsFetched(segmentId, snapshot.rows1.size() + snapshot.rows2.size());
                        budgetGuard.addRowsScanned(snapshot.rows1.size() + snapshot.rows2.size());

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
    private Map<List<Object>, Object[]> queryRowsByKeys(TableSegment segment,
                                                        Set<List<Object>> keys,
                                                        Map<String, DataType> columnTypes,
                                                        InfoTreeRecorder infoTreeRecorder,
                                                        String segmentId) {
        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<List<Object>, Object[]> rowMap = new HashMap<>();
        int keyColumnCount = segment.getKeyColumns().size();

        // Query in bounded batches so a huge difference set never materializes
        // every fetched row in an intermediate list at once.
        List<List<Object>> keyList = new ArrayList<>(keys);
        for (int i = 0; i < keyList.size(); i += KEY_QUERY_BATCH_SIZE) {
            int end = Math.min(i + KEY_QUERY_BATCH_SIZE, keyList.size());
            Set<List<Object>> batch = new HashSet<>(keyList.subList(i, end));
            List<Object[]> rows = segment.getValuesByKeys(batch);
            infoTreeRecorder.addQueryCount(segmentId, 1);
            long batchBytes = estimateRowsBytes(rows);
            infoTreeRecorder.addBytesFetched(segmentId, batchBytes);
            budgetGuard.addBytesFetched(batchBytes);
            budgetGuard.addRowsScanned(rows.size());
            for (Object[] row : rows) {
                List<Object> primaryKey = normalizeKeyValues(
                        Arrays.asList(row).subList(0, Math.min(keyColumnCount, row.length)),
                        segment.getKeyColumns(),
                        columnTypes);
                rowMap.put(primaryKey, row);
            }
        }

        return rowMap;
    }

    private static long estimateRowsBytes(List<Object[]> rows) {
        long bytes = 0L;
        for (Object[] row : rows) {
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                if (value != null) {
                    bytes += value.toString().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return bytes;
    }

    private static long estimateKeyHashBytes(List<Object> key, String hash) {
        long bytes = hash == null ? 0L : hash.getBytes(StandardCharsets.UTF_8).length;
        if (key != null) {
            for (Object value : key) {
                if (value != null) {
                    bytes += value.toString().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return bytes;
    }

    private List<Object> normalizeKeyValues(List<?> keyValues,
                                            List<String> keyColumns,
                                            Map<String, DataType> columnTypes) {
        List<Object> normalized = new ArrayList<>(keyValues.size());
        for (int i = 0; i < keyValues.size(); i++) {
            String columnName = i < keyColumns.size() ? keyColumns.get(i) : null;
            DataType dataType = columnName != null
                    ? columnTypes.getOrDefault(columnName, DataType.UNKNOWN)
                    : DataType.UNKNOWN;
            normalized.add(ValueNormalizer.normalizeKey(keyValues.get(i), dataType));
        }
        return List.copyOf(normalized);
    }

    private static class RowHashSnapshot {
        private final Set<List<Object>> keysOnlyInTable1;
        private final Map<List<Object>, List<Object>> rawKeysOnlyInTable1;
        private final Set<List<Object>> keysOnlyInTable2;
        private final Map<List<Object>, List<Object>> rawKeysOnlyInTable2;
        private final Set<List<Object>> mismatchedKeys;
        private final Map<List<Object>, List<Object>> rawKeysMismatchedIn1;
        private final Map<List<Object>, List<Object>> rawKeysMismatchedIn2;
        private final long table1RowCount;
        private final long table2RowCount;
        private final Map<String, DataType> columnTypes1;
        private final Map<String, DataType> columnTypes2;
        /** 任一侧存在重复键：row-hash 每键只保留一行，无法表达多重集，退回全量精确比较。 */
        private final boolean duplicateKeys;

        private RowHashSnapshot(Set<List<Object>> keysOnlyInTable1,
                                Map<List<Object>, List<Object>> rawKeysOnlyInTable1,
                                Set<List<Object>> keysOnlyInTable2,
                                Map<List<Object>, List<Object>> rawKeysOnlyInTable2,
                                Set<List<Object>> mismatchedKeys,
                                Map<List<Object>, List<Object>> rawKeysMismatchedIn1,
                                Map<List<Object>, List<Object>> rawKeysMismatchedIn2,
                                long table1RowCount,
                                long table2RowCount,
                                Map<String, DataType> columnTypes1,
                                Map<String, DataType> columnTypes2,
                                boolean duplicateKeys) {
            this.keysOnlyInTable1 = keysOnlyInTable1;
            this.rawKeysOnlyInTable1 = rawKeysOnlyInTable1;
            this.keysOnlyInTable2 = keysOnlyInTable2;
            this.rawKeysOnlyInTable2 = rawKeysOnlyInTable2;
            this.mismatchedKeys = mismatchedKeys;
            this.rawKeysMismatchedIn1 = rawKeysMismatchedIn1;
            this.rawKeysMismatchedIn2 = rawKeysMismatchedIn2;
            this.table1RowCount = table1RowCount;
            this.table2RowCount = table2RowCount;
            this.columnTypes1 = columnTypes1;
            this.columnTypes2 = columnTypes2;
            this.duplicateKeys = duplicateKeys;
        }

        private int totalDifferences() {
            return keysOnlyInTable1.size() + keysOnlyInTable2.size() + mismatchedKeys.size();
        }
    }

    private static class RowHashSide {
        private final Map<List<Object>, RowHashEntry> entries = new LinkedHashMap<>();
        private long total;

        /** @return true when the key already existed (duplicate key on this side) */
        private boolean put(List<Object> normalizedKey, List<Object> rawKey, String hash) {
            boolean duplicate = entries.put(normalizedKey, new RowHashEntry(rawKey, hash)) != null;
            total++;
            return duplicate;
        }

        private RowHashEntry remove(List<Object> normalizedKey) {
            return entries.remove(normalizedKey);
        }

        private Map<List<Object>, List<Object>> remainingRawKeys() {
            Map<List<Object>, List<Object>> rawKeys = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, RowHashEntry> entry : entries.entrySet()) {
                rawKeys.put(entry.getKey(), entry.getValue().rawKey);
            }
            return rawKeys;
        }

        private long total() {
            return total;
        }
    }

    private static class RowHashEntry {
        private final List<Object> rawKey;
        private final String hash;

        private RowHashEntry(List<Object> rawKey, String hash) {
            this.rawKey = rawKey;
            this.hash = hash;
        }
    }

    private static class RowChecksumDiffData {
        private final RowHashSnapshot plan;
        private final Map<List<Object>, Object[]> data1;
        private final Map<List<Object>, Object[]> data2;
        private final Map<String, DataType> columnTypes1;
        private final Map<String, DataType> columnTypes2;

        private RowChecksumDiffData(RowHashSnapshot plan, Map<List<Object>, Object[]> data1,
                                    Map<List<Object>, Object[]> data2,
                                    Map<String, DataType> columnTypes1,
                                    Map<String, DataType> columnTypes2) {
            this.plan = plan;
            this.data1 = data1;
            this.data2 = data2;
            this.columnTypes1 = columnTypes1;
            this.columnTypes2 = columnTypes2;
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
     * Extract column types from table segment schema.
     */
    /**
     * 两侧键列的排序语义是否可比。
     *
     * <p>同一逻辑键在两侧可能是不同族类型（一侧 INT、另一侧 VARCHAR）。此时区间谓词的含义
     * 不一致：分段边界跨侧复用、"&gt; 对端 maxKey" 尾扫都会按各自的排序解释，把区间内的正常行
     * 误判为单侧缺失（dq_cmp_typediff_* 场景实测 86/200）。检测到该形态时应退化为整表按
     * 归一化键比较。
     */
    private boolean keyOrderingCompatible(TableSegment table1, TableSegment table2) {
        List<String> keys1 = table1.getKeyColumns();
        List<String> keys2 = table2.getKeyColumns();
        if (keys1 == null || keys2 == null || keys1.isEmpty() || keys1.size() != keys2.size()) {
            return false;
        }
        Map<String, DataType> types1 = extractColumnTypes(table1);
        Map<String, DataType> types2 = extractColumnTypes(table2);
        for (int index = 0; index < keys1.size(); index++) {
            DataType type1 = types1.get(keys1.get(index));
            DataType type2 = types2.get(keys2.get(index));
            if (type1 == null || type2 == null) {
                // 类型未知时保持既有行为，避免影响无法探明 schema 的数据源
                continue;
            }
            if (isNumericKeyType(type1) != isNumericKeyType(type2)
                    || isCharacterKeyType(type1) != isCharacterKeyType(type2)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumericKeyType(DataType dataType) {
        switch (dataType) {
            case INTEGER:
            case BIGINT:
            case SMALLINT:
            case TINYINT:
            case DECIMAL:
            case NUMERIC:
            case FLOAT:
            case DOUBLE:
            case REAL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isCharacterKeyType(DataType dataType) {
        switch (dataType) {
            case VARCHAR:
            case CHAR:
            case TEXT:
            case CLOB:
            case LONGVARCHAR:
                return true;
            default:
                return false;
        }
    }

    private Map<String, DataType> extractColumnTypes(TableSegment segment) {
        Optional<TableSchema> schema = segment.getSchema();
        if (!schema.isPresent()) {
            schema = discoverSchema(segment);
        }
        Map<String, DataType> types = new HashMap<>();
        if (schema.isPresent()) {
            // Only expose datatypes for relevant columns so the local diff can normalize values correctly.
            for (String column : segment.getRelevantColumns()) {
                DataType type = schema.get().getColumnType(column);
                if (type != null) {
                    types.put(column, type);
                }
            }
        } else {
            log.warn("Schema unavailable for segment {}, values normalize as UNKNOWN",
                    segment.getDisplayName());
        }
        return types;
    }

    private Optional<TableSchema> discoverSchema(TableSegment segment) {
        if (segment.hasRelationSource() || segment.getTablePath() == null || segment.getDatabase() == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    segment.getDatabase().getTableSchema(segment.getTablePath().getComponents()));
        } catch (Exception e) {
            log.warn("Failed to discover schema for segment {}", segment.getDisplayName(), e);
            return Optional.empty();
        }
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
        long maxRows = estimateSegmentMaxRows(table1, table2);
        return bisectAndDiffSegmentsEnhanced(table1, table2, infoTreeRecorder, level, maxRows, adaptiveFactor, segmentId);
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
        if (maxSize > LARGE_TABLE_ROW_THRESHOLD) {
            adaptiveFactor = Math.min(config.getBisectionFactor() * 2, 64);
            log.debug("Large dataset ({}), using adaptive factor: {} (base factor * 2)", maxSize, adaptiveFactor);
        } else if (maxSize > MEDIUM_TABLE_ROW_THRESHOLD) {
            adaptiveFactor = config.getBisectionFactor();
            log.debug("Medium dataset ({}), using adaptive factor: {} (base factor)", maxSize, adaptiveFactor);
        } else {
            adaptiveFactor = Math.max(config.getBisectionFactor() / 2, 4);
            log.debug("Small dataset ({}), using adaptive factor: {} (base factor / 2)", maxSize, adaptiveFactor);
        }
        
        return adaptiveFactor;
    }

    private long estimateSegmentMaxRows(TableSegment table1, TableSegment table2) {
        return Math.max(estimateSegmentRows(table1), estimateSegmentRows(table2));
    }

    private long estimateSegmentRows(TableSegment table) {
        long size = table.approximateSize();
        if (size >= 0) {
            return size;
        }
        try {
            return table.count();
        } catch (Exception e) {
            log.warn("Failed to estimate segment row count for adaptive bisection", e);
            return config.getBisectionThreshold();
        }
    }

    /**
     * Create initial bounds and start bisection.
     */
    private CompletableFuture<Void> createInitialBoundsAndBisection(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder, int level, int bisectionFactor,
            String parentSegmentId) {

        return getInitialBounds(table1, table2, infoTreeRecorder)
                .thenCompose(bounds -> {
                    TableSegment bounded1 = createBoundedSegment(table1, bounds.table1Bounds);
                    TableSegment bounded2 = createBoundedSegment(table2, bounds.table2Bounds);

                    // 复合键空表/边界查询失败时 getInitialBounds 返回 null，
                    // createBoundedSegment 会原样返回未定界段；若继续递归会以同一 level
                    // 反复进入这里，形成无进展死循环。此时退回归一化键的本地比较。
                    if (!bounded1.isBounded() || !bounded2.isBounded()) {
                        log.warn("Unable to establish key bounds for {} / {}; "
                                        + "falling back to normalized-key comparison",
                                table1.getDisplayName(), table2.getDisplayName());
                        return performLocalComparison(table1, table2, infoTreeRecorder,
                                parentSegmentId, true);
                    }

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
            // 上界包含性必须与 larger 段一致，否则两侧段范围错位（一侧含边界行、另一侧不含）
            TableSegment correspondingSegment = smallerTable.toBuilder()
                    .minKey(largerSegment.getMinKey())
                    .maxKey(largerSegment.getMaxKey())
                    .upperBoundInclusive(largerSegment.isUpperBoundInclusive())
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
                        return diffSegmentsWithParent(table1, table2, infoTreeRecorder, maxRows, level, parentSegmentId,
                                        lease::release)
                                .whenComplete((ignored, error) -> lease.release());
                    } catch (Throwable error) {
                        lease.release();
                        return CompletableFuture.failedFuture(error);
                    }
                });
    }

    private static void releaseQuietly(Runnable releasePermit) {
        if (releasePermit == null) {
            return;
        }
        try {
            releasePermit.run();
        } catch (RuntimeException e) {
            log.warn("Failed to release segment permit: {}", e.getMessage());
        }
    }

    /**
     * 把容差内行从差异列表里分走：默认口径下它们不计入 totalDifferences，也不落差异 sink。
     * REPORT 口径原样返回（仍然算差异），只靠 metadata 让报告能标出来。
     * 单独抽出来是为了让"谁计数"这件事只有一个入口，也便于直接单测。
     */
    List<DiffRow> partitionWithinTolerance(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows == null ? List.of() : rows;
        }
        if (config.getWithinToleranceMode() != com.consilens.core.compare.WithinToleranceMode.IGNORE) {
            return rows;
        }
        List<DiffRow> realDifferences = new ArrayList<>(rows.size());
        for (DiffRow row : rows) {
            if (row != null && row.isWithinTolerance()) {
                collectWithinToleranceRow(row);
            } else {
                realDifferences.add(row);
            }
        }
        return realDifferences;
    }

    /** 容差内行数（含因样本上限未保留的部分）。 */
    long withinToleranceCount() {
        return withinToleranceCount.get();
    }

    /** 已保留的容差内行样本（上限由 withinToleranceSamples 控制）。 */
    List<DiffRow> withinToleranceRows() {
        synchronized (withinToleranceRows) {
            return new ArrayList<>(withinToleranceRows);
        }
    }

    /**
     * 收集容差内行：计数永远累加（报告里要显示"有几行被容差吸收"），行样本按上限保留。
     * 去重身份与差异行分开，避免同一把键既算差异又算"容差内"时互相吞并。
     */
    private void collectWithinToleranceRow(DiffRow row) {
        Object occurrence = row.getMetadata() == null ? null : row.getMetadata().get("occurrence");
        String identity = row.getOperation() + "\u0001" + row.getPrimaryKey()
                + "\u0001" + (occurrence == null ? "" : occurrence);
        if (!reportedWithinToleranceKeys.add(identity)) {
            return;
        }
        withinToleranceCount.incrementAndGet();
        int limit = config.getWithinToleranceSamples();
        if (limit <= 0) {
            return;
        }
        synchronized (withinToleranceRows) {
            if (withinToleranceRows.size() < limit) {
                withinToleranceRows.add(row);
            }
        }
    }

    /**
     * 容差内行的统一入口：
     * <ul>
     *   <li>任何口径都计数并保留样本——报告要能说"这 1 行是容差吸收的"；</li>
     *   <li>REPORT 口径下同时把它们并入差异列表（回到"容差内也报差异"的旧口径）。</li>
     * </ul>
     * IGNORE 口径下不并入，因此它们不会落 diff sink、也不会进入 totalDifferences。
     */
    void storeWithinToleranceRows(InfoTreeRecorder infoTreeRecorder, List<DiffRow> differences,
                                  List<DiffRow> rows, String segmentId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (DiffRow row : rows) {
            collectWithinToleranceRow(row);
        }
        if (config.getWithinToleranceMode() == com.consilens.core.compare.WithinToleranceMode.REPORT) {
            differences.addAll(rows);
            return;
        }
        infoTreeRecorder.addMetric(segmentId, "withinTolerance", rows.size());
    }

    /**
     * Store differences in the internal collection for final result.
     */
    private void storeDifferences(InfoTreeRecorder infoTreeRecorder, List<DiffRow> differences, String segmentId) {
        if (differences == null || differences.isEmpty()) {
            return;
        }
        differences = partitionWithinTolerance(differences);
        if (differences.isEmpty()) {
            return;
        }
        // 主扫描与尾部补扫可能对同一把键各报一次（实测 dq_cmp_edge_* 的 'abc' 被报两次），
        // 按 (操作, 主键, occurrence) 去重：跨"主扫描/尾部补扫"避免重复计数；
        // occurrence 必须参与——重复键（同键多行）场景下同键会有多条同类差异，
        // 只按 (操作, 主键) 去重会把它们吞掉（实测 edge_dup 少了 2 条）。
        List<DiffRow> unique = new ArrayList<>(differences.size());
        for (DiffRow row : differences) {
            Object occurrence = row.getMetadata() == null ? null : row.getMetadata().get("occurrence");
            String identity = row.getOperation() + "\u0001" + row.getPrimaryKey()
                    + "\u0001" + (occurrence == null ? "" : occurrence);
            if (reportedRowKeys.add(identity)) {
                unique.add(row);
                reportedKeyOps.add(keyOpIdentity(row.getOperation(), row.getPrimaryKey()));
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        differences = unique;
        ensureDiffLimit(differences.size());
        infoTreeRecorder.markFirstDifference();

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
     * Scan rows that fall OUTSIDE the other side's observed key range. Bisection
     * segments derive from the larger table's key space, so rows beyond the
     * smaller range's max (or below its min) are never visited by any segment;
     * they are fetched directly here and emitted as presence differences.
     *
     * <p>Two complementary passes, deduplicated by primary key:
     * <ol>
     *   <li>Range pass — rows beyond the other side's bound per Java key ordering.
     *   Catches multi-row leaks when both sides order keys like the JVM does.</li>
     *   <li>Extreme-key existence pass — the four extreme keys (each side's own
     *   min/max, as reported by that side) are probed on the opposite side with an
     *   EQUALITY predicate. Equality is immune to database collation ordering
     *   (e.g. MySQL utf8mb4_0900_ai_ci / SQL Server Windows collations order
     *   {@code '_'} before digits, which silently hides a tail row from every
     *   ordering-based range predicate).</li>
     * </ol>
     */
    private void scanAndStoreTailDifferences(TableSegment table1, TableSegment table2,
                                             InfoTreeRecorder infoTreeRecorder,
                                             ChecksumResult bounds1, ChecksumResult bounds2,
                                             TableSegment largerTable) {
        try {
            if (bounds1 == null || bounds2 == null
                    || bounds1.getMinKey() == null || bounds1.getMaxKey() == null
                    || bounds2.getMinKey() == null || bounds2.getMaxKey() == null) {
                return;
            }

            // Only the side that is NOT the segmentation base (the smaller table)
            // can have rows outside the base's key space — those are never visited
            // by any segment. Rows of the base side are fully covered already and
            // must not be counted twice.
            boolean baseIsTable1 = largerTable == table1;
            Set<List<Object>> emittedKeys = new HashSet<>();

            // 两侧键列排序语义不同（INT vs VARCHAR 等）时，"> 对端 maxKey" 在另一端按不同
            // 排序解释，会把区间内的正常行误判为单侧缺失；此分支只保留等值极值兜底。
            boolean orderingCompatible = keyOrderingCompatible(table1, table2);
            if (!orderingCompatible) {
                log.warn("Skipping range-based tail scan for {} vs {}: key orderings differ",
                        table1.getDisplayName(), table2.getDisplayName());
            }

            // 区间尾扫只在两侧键序可比时进行；不可比时只保留等值极值探测（见下方 backstop）
            if (!baseIsTable1) {
                if (orderingCompatible && compareKeys(bounds1.getMaxKey(), bounds2.getMaxKey()) > 0) {
                    // table1 is the smaller side; rows of table1 beyond table2's range leak
                    appendTailRows(table1, bounds2.getMaxKey(), null, DiffOperation.TARGET_MISSING,
                            table1, infoTreeRecorder, "tail-beyond-target-max", emittedKeys);
                }
                if (orderingCompatible && compareKeys(bounds1.getMinKey(), bounds2.getMinKey()) < 0) {
                    appendTailRows(table1, null, bounds2.getMinKey(), DiffOperation.TARGET_MISSING,
                            table1, infoTreeRecorder, "tail-below-target-min", emittedKeys);
                }
            } else {
                if (orderingCompatible && compareKeys(bounds2.getMaxKey(), bounds1.getMaxKey()) > 0) {
                    // table2 is the smaller side; rows of table2 beyond table1's range leak
                    appendTailRows(table2, bounds1.getMaxKey(), null, DiffOperation.SOURCE_MISSING,
                            table2, infoTreeRecorder, "tail-beyond-source-max", emittedKeys);
                }
                if (orderingCompatible && compareKeys(bounds2.getMinKey(), bounds1.getMinKey()) < 0) {
                    appendTailRows(table2, null, bounds1.getMinKey(), DiffOperation.SOURCE_MISSING,
                            table2, infoTreeRecorder, "tail-below-source-min", emittedKeys);
                }
            }

            // Collation-robust backstop: probe each side's own extreme keys on the
            // opposite side with equality. Only runs when the Java-ordering view of
            // the two extremes differs (equal extremes are already covered by the
            // main scan) and the key was not emitted by the range pass above.
            if (!baseIsTable1) {
                checkExtremeKeyOnOtherSide(bounds1.getMaxKey(), bounds2.getMaxKey(), table1, table2,
                        DiffOperation.TARGET_MISSING, infoTreeRecorder, emittedKeys, "tail-extreme-max");
                checkExtremeKeyOnOtherSide(bounds1.getMinKey(), bounds2.getMinKey(), table1, table2,
                        DiffOperation.TARGET_MISSING, infoTreeRecorder, emittedKeys, "tail-extreme-min");
            } else {
                checkExtremeKeyOnOtherSide(bounds2.getMaxKey(), bounds1.getMaxKey(), table2, table1,
                        DiffOperation.SOURCE_MISSING, infoTreeRecorder, emittedKeys, "tail-extreme-max");
                checkExtremeKeyOnOtherSide(bounds2.getMinKey(), bounds1.getMinKey(), table2, table1,
                        DiffOperation.SOURCE_MISSING, infoTreeRecorder, emittedKeys, "tail-extreme-min");
            }
        } catch (Exception e) {
            log.warn("Tail difference scan failed; presence diffs beyond the compared range may be missed", e);
        }
    }

    /**
     * Verify that {@code extremeKey} (an extreme of {@code ownerTable}) also exists
     * on {@code otherTable}. If not, emit the owner's row as a presence difference.
     * Skipped when the other side reports the same extreme key (covered by the main
     * scan) or the key was already emitted by the range pass.
     */
    private void checkExtremeKeyOnOtherSide(List<Object> extremeKey, List<Object> otherExtremeKey,
                                            TableSegment ownerTable, TableSegment otherTable,
                                            DiffOperation operation, InfoTreeRecorder infoTreeRecorder,
                                            Set<List<Object>> emittedKeys, String segmentId) {
        try {
            if (extremeKey == null || extremeKey.isEmpty()
                    || compareKeys(extremeKey, otherExtremeKey) == 0
                    || emittedKeys.contains(extremeKey)) {
                return;
            }
            List<Object[]> hits = otherTable.getValuesByKeys(Collections.singleton(extremeKey));
            if (!hits.isEmpty()) {
                return;
            }
            List<Object[]> ownerRows = ownerTable.getValuesByKeys(Collections.singleton(extremeKey));
            if (ownerRows.isEmpty()) {
                return;
            }
            int keyCount = ownerTable.getKeyColumns().size();
            List<Object> pk = new ArrayList<>(Arrays.asList(ownerRows.get(0)).subList(0, keyCount));
            List<Object> values = new ArrayList<>(Arrays.asList(ownerRows.get(0)));
            if (reportedKeyOps.contains(keyOpIdentity(operation, pk))) {
                emittedKeys.add(extremeKey);
                return;
            }
            List<String> columnNames = concatNames(ownerTable.getKeyColumns(), ownerTable.getExtraColumns());
            DiffRow diffRow = operation == DiffOperation.SOURCE_MISSING
                    ? DiffRow.added(pk, values, columnNames)
                    : DiffRow.removed(pk, values, columnNames);
            log.info("Tail scan [{}]: extreme key {} missing on the opposite side (collation-order backstop)",
                    segmentId, pk);
            storeDifferences(infoTreeRecorder, Collections.singletonList(diffRow), segmentId);
            emittedKeys.add(extremeKey);
        } catch (Exception e) {
            log.warn("Tail extreme-key check [{}] failed; diff may be missed", segmentId, e);
        }
    }

    private static String keyOpIdentity(DiffOperation operation, List<Object> primaryKey) {
        return operation + "\u0001" + primaryKey;
    }

    /**
     * Fetch rows of `scanTable` within [minKey, maxKey] (null = unbounded side),
     * drop rows equal to the adjacent-side boundary key, and emit them as
     * presence differences attributed to `ownerTable`'s columns.
     * Emitted primary keys are added to {@code emittedKeys} for deduplication.
     */
    private void appendTailRows(TableSegment scanTable, List<Object> minKey, List<Object> maxKey,
                                DiffOperation operation, TableSegment ownerTable,
                                InfoTreeRecorder infoTreeRecorder, String segmentId,
                                Set<List<Object>> emittedKeys) throws Exception {
        TableSegment bounded = scanTable.toBuilder()
                .minKey(Optional.ofNullable(minKey))
                .maxKey(Optional.ofNullable(maxKey))
                .upperBoundInclusive(true)
                .build();
        // 尾扫是"超出一侧键范围"行的唯一防线，取数失败会导致静默漏报（实测 ClickHouse
        // QUERY_WAS_CANCELLED 一次即少 1 条 SM）。瞬时失败（查询被取消/超时）重试几次再放弃。
        List<Object[]> rows = null;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= TAIL_SCAN_MAX_ATTEMPTS; attempt++) {
            try {
                rows = bounded.getValues();
                break;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Tail scan [{}] fetch attempt {}/{} failed: {}",
                        segmentId, attempt, TAIL_SCAN_MAX_ATTEMPTS, e.getMessage());
                try {
                    Thread.sleep(TAIL_RETRY_BASE_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (rows == null) {
            throw lastError != null ? lastError : new IllegalStateException("tail scan fetch failed");
        }
        List<String> columnNames = concatNames(ownerTable.getKeyColumns(), ownerTable.getExtraColumns());
        int keyCount = ownerTable.getKeyColumns().size();
        List<Object> boundary = minKey != null ? minKey : maxKey;

        List<DiffRow> diffRows = new ArrayList<>();
        for (Object[] row : rows) {
            List<Object> pk = new ArrayList<>(Arrays.asList(row).subList(0, keyCount));
            // drop the shared boundary row itself (already covered by the main scan)
            if (compareKeys(pk, boundary) == 0) {
                continue;
            }
            // A key already reported by the main scan (any occurrence) must not be
            // emitted again: with union segmentation the tail scan is a safety net,
            // and its rows overlap the main scan by design.
            if (reportedKeyOps.contains(keyOpIdentity(operation, pk))) {
                continue;
            }
            List<Object> values = new ArrayList<>(Arrays.asList(row));
            DiffRow diffRow = operation == DiffOperation.SOURCE_MISSING
                    ? DiffRow.added(pk, values, columnNames)
                    : DiffRow.removed(pk, values, columnNames);
            diffRows.add(diffRow);
            emittedKeys.add(pk);
        }
        if (!diffRows.isEmpty()) {
            log.info("Tail scan [{}]: {} presence-only rows outside the other side's key range",
                    segmentId, diffRows.size());
            storeDifferences(infoTreeRecorder, diffRows, segmentId);
        }
    }

    private List<String> concatNames(List<String> keyColumns, List<String> extraColumns) {
        List<String> names = new ArrayList<>();
        if (keyColumns != null) names.addAll(keyColumns);
        if (extraColumns != null) names.addAll(extraColumns);
        return names;
    }

    private int compareKeys(List<Object> ka, List<Object> kb) {
        int n = Math.min(ka.size(), kb.size());
        for (int i = 0; i < n; i++) {
            int cmp = compareKeyPart(ka.get(i), kb.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(ka.size(), kb.size());
    }

    /**
     * 比较单个键分量。
     *
     * <p>边界来自 JDBC 时可能是 {@link java.math.BigDecimal}，而行内主键是驱动/归一化后的
     * 字符串；两者混用时按数值比较，避免 {@code "9999" > "20000"} 这类字典序错误。
     * 两侧都是字符串时保持字典序（VARCHAR 键不能按数值比较）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeyPart(Object va, Object vb) {
        if (va == null && vb == null) {
            return 0;
        }
        if (va == null) {
            return -1;
        }
        if (vb == null) {
            return 1;
        }
        boolean numeric = va instanceof Number || vb instanceof Number;
        if (numeric) {
            BigDecimal left = toBigDecimal(va);
            BigDecimal right = toBigDecimal(vb);
            if (left != null && right != null) {
                return left.compareTo(right);
            }
        }
        if (va.getClass() == vb.getClass() && va instanceof Comparable) {
            return ((Comparable) va).compareTo(vb);
        }
        if (va instanceof Number && vb instanceof Number) {
            return Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
        }
        return String.valueOf(va).compareTo(String.valueOf(vb));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Create final diff result.
     */
    private DiffResult createDiffResult(TableSegment table1, TableSegment table2, Void result, long sourceRowCount,
            long targetRowCount, long durationMs) {
        // 所有 main-flow 分支的必经点：在此统一补扫"超出对端键范围"的行，
        // 保证任何分段策略下尾部行差异都不会漏检。
        if (initialBounds1 != null && initialBounds2 != null && tailInfoRecorder != null) {
            scanAndStoreTailDifferences(table1, table2, tailInfoRecorder, initialBounds1, initialBounds2,
                    initialBounds1.getCount() >= initialBounds2.getCount() ? table1 : table2);
        }

        long sourceMissing = sourceMissingCount.get();
        long targetMissing = targetMissingCount.get();
        long mismatched = mismatchCount.get();
        long totalDifferences = sourceMissing + targetMissing + mismatched;

        DiffResult.DiffStatistics statistics = DiffResult.DiffStatistics.builder()
                .sourceRowCount(sourceRowCount)
                .targetRowCount(targetRowCount)
                .sourceMissingCount(sourceMissing)
                .targetMissingCount(targetMissing)
                .mismatchCount(mismatched)
                .totalDifferences(totalDifferences)
                .processingTimeMs(durationMs)
                .unchangedCount(Math.max(0, Math.min(sourceRowCount, targetRowCount) - mismatched))
                .withinToleranceCount(withinToleranceCount.get())
                .build();

        completeDiff(statistics);

        // REPORT 口径下容差内行已经作为普通差异进入 differences，不能再作为"样本"重复下发，
        // 否则消费方（CSV 旁挂文件 / console 块）会把同一行列两遍。
        List<DiffRow> withinTolerance = config.getWithinToleranceMode()
                == com.consilens.core.compare.WithinToleranceMode.IGNORE
                ? withinToleranceRows() : List.of();
        return DiffResult.builder()
                .differences(getCollectedDifferences())
                .withinToleranceDifferences(withinTolerance)
                .statistics(statistics)
                .sourceTablePath(table1.getTablePath())
                .targetTablePath(table2.getTablePath())
                .completedAt(Instant.now())
                .metadata(new HashMap<>(Map.of(
                        // 实际执行的校验和算法（可能已被 ChecksumPlanExecutor 按方言能力降级），
                        // 随 artifact metadata 暴露到 diff-report，供测试与运维断言。
                        "checksumAlgorithm", String.valueOf(config.getChecksumAlgorithm()))))
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
        return "seg" + encodeKeyParts(minKey, maxKey, where, limitOffset, String.valueOf(index));
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
            if (jdbcUrl == null) {
                jdbcUrl = "";
            }
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
        
        // Every dimension that changes the selected row set must be part of the key:
        // an exclusive [k,k) empty segment and an inclusive [k,k] segment used to share
        // the same key, so the empty count could be reused for a segment that has rows.
        return CHECKSUM_CACHE_VERSION + "_" + databaseId + "_" + encodeKeyParts(
                tablePath,
                segment.getMinKey().map(Object::toString).orElse("null"),
                segment.getMaxKey().map(Object::toString).orElse("null"),
                String.valueOf(segment.isUpperBoundInclusive()),
                segment.getWhereClause().orElse("null"),
                segment.getLimitOffset().map(Object::toString).orElse("null"),
                segment.hasRelationSource() ? segment.getRelationFromSql() : "",
                String.join(",", segment.getKeyColumns()),
                String.join(",", segment.getExtraColumns()));
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
