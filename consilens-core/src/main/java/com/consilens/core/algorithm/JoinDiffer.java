package com.consilens.core.algorithm;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.segment.TableSegmenter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.thread.ExecutorProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

/**
 * Enhanced Join-based table differ implementation.
 */
@Slf4j
public class JoinDiffer extends TableDiffer implements AutoCloseable {

    private final boolean validateUniqueKeys;
    /** 开放档命中重复键时的划分谓词（段级 join 计划必须复用同一过滤，否则 SQL 侧仍会 N×M）。 */
    private volatile DupKeySplit activeSplit = DupKeySplit.none();
    private final JoinPerformanceMonitor performanceMonitor;
    private final AtomicLong sourceMissingCount = new AtomicLong(0);
    private final AtomicLong targetMissingCount = new AtomicLong(0);
    private final AtomicLong mismatchCount = new AtomicLong(0);
    private final AtomicLong segmentSequence = new AtomicLong(0);
    private final BudgetGuard budgetGuard;

    public JoinDiffer(DifferConfig config, JoinDifferOptions options) {
        this(config, options, null);
    }

    public JoinDiffer(DifferConfig config, JoinDifferOptions options, ExecutorProvider executorProvider) {
        super(config,
                executorProvider != null ? executorProvider : new ExecutorProvider(config.getConcurrencyConfig()),
                executorProvider == null);
        this.validateUniqueKeys = options.isValidateUniqueKeys();
        this.performanceMonitor = new JoinPerformanceMonitor();
        this.budgetGuard = new BudgetGuard(config.getMaxDurationMs(), config.getMaxRowsScanned(),
                config.getMaxBytesFetched());
    }

    @Override
    protected CompletableFuture<DiffResult> diffTablesRoot(
            TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder) {

        log.info("Starting Enhanced JoinDiff between {} and {} in database {}",
                table1.getTablePath(), table2.getTablePath(), safeDatabaseName(table1));

        performanceMonitor.startJoin("root");
        infoTreeRecorder.startNode("joindiff", "diff", "joindiff", "algorithm", "joindiff", 1);
        infoTreeRecorder.recordRange("joindiff",
                table1.getMinKey().map(Object::toString).orElse(null),
                table1.getMaxKey().map(Object::toString).orElse(null),
                table1.getWhereClause().orElse(null));

        // Validate both tables are in the same database
        validateSameDatabase(table1, table2);

        log.debug("Table1 database: {}, Table2 database: {}",
                safeDatabaseName(table1), safeDatabaseName(table2));

        DatabaseAdapter database = table1.getDatabase();

        // 严格档（validateUniqueKeys=true）的唯一键校验已在 TableDiffer.diffTables 入口完成
        // （命中即失败，不会走到这里）。
        // 开放档（默认）下重复键按多重集语义处理：FULL OUTER JOIN 对重复键会 N×M 倍增，
        // 因此把"任一侧键重复"的行交给多重集配对引擎（与 checksum/streaming 同一实现），
        // join 的 SQL 只跑"两侧键都唯一"的子集（此时 1:1，无倍增，结果与多重集语义一致）。
        boolean openTier = !validateUniqueKeys;
        CompletableFuture<DupKeySplit> splitFuture = openTier
                ? CompletableFuture.supplyAsync(() -> prepareDupKeySplit(table1, table2),
                        executorProvider.getIoExecutor())
                : CompletableFuture.completedFuture(DupKeySplit.none());

        return splitFuture
                .thenCompose(split -> {
                    performanceMonitor.recordStage("preparations_complete");

                    activeSplit = split;
                    CompletableFuture<Void> diffFuture;
                    if (split.localOnly()) {
                        // 方言不支持键多重性过滤（如 Doris）：整表本地多重集配对，跳过 SQL join
                        diffFuture = CompletableFuture.runAsync(
                                () -> compareWholeTableLocally(table1, table2, infoTreeRecorder),
                                executorProvider.getCpuExecutor());
                    } else {
                        // Execute the segment diff directly; a root-level query plan would be
                        // discarded because each segment builds its own plan.
                        performanceMonitor.recordStage("join_started");
                        diffFuture = diffSegments(table1, table2, infoTreeRecorder, 0L, 0)
                                .thenAccept(result -> {
                                    // 重复键子集：本地多重集配对（结果与 checksum/streaming 逐数字一致）
                                    if (split.hasDuplicates()) {
                                        compareDuplicateKeyRows(split, table1, table2, infoTreeRecorder);
                                    }
                                });
                    }

                    return diffFuture
                            .thenCompose(result -> {
                                performanceMonitor.endJoin("root");
                                infoTreeRecorder.endNode("joindiff");
                                // Get table row counts for statistics
                                CompletableFuture<Long> table1Count = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return database.count(table1);
                                    } catch (Exception e) {
                                        log.warn("Failed to count table1 rows", e);
                                        return 0L;
                                    }
                                }, executorProvider.getIoExecutor());
                                CompletableFuture<Long> table2Count = CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return database.count(table2);
                                    } catch (Exception e) {
                                        log.warn("Failed to count table2 rows", e);
                                        return 0L;
                                    }
                                }, executorProvider.getIoExecutor());
                                return table1Count.thenCombine(table2Count,
                                        (count1, count2) -> createDiffResult(table1, table2, result, count1, count2));
                            });
                })
                .exceptionally(e -> {
                    performanceMonitor.endJoin("root");
                    infoTreeRecorder.endNode("joindiff");
                    // join 的"业务性拒绝"（重复键不得 N×M 放大、计划不可用等）要保留原始信息，
                    // 不能被 "JoinDiff failed" 包一层导致拒绝理由丢失。
                    Throwable cause = e;
                    while ((cause instanceof java.util.concurrent.CompletionException
                            || cause instanceof java.util.concurrent.ExecutionException)
                            && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof ConnectorException) {
                        log.warn("JoinDiff refused: {}", cause.getMessage());
                        throw (ConnectorException) cause;
                    }
                    if (cause instanceof com.consilens.core.exception.BudgetExceededException) {
                        // 预算拒绝必须原样上抛：上层据此标成"结果不完整、不可重试"
                        throw (com.consilens.core.exception.BudgetExceededException) cause;
                    }
                    log.error("JoinDiff failed", e);
                    throw new RuntimeException("JoinDiff failed", e);
                });
    }

    /**
     * P2-2：join 不再对整表只发一条 SQL。根段先取边界，随后按并集键空间分段，
     * 每段各发一条 join 查询；小表（低于 bisectionThreshold）保持单条 SQL。
     */
    @Override
    protected CompletableFuture<Void> diffSegments(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level) {

        if (level == 0 && (!table1.isBounded() || !table2.isBounded())) {
            return establishJoinBounds(table1, table2)
                    .thenCompose(bounds -> bounds.bounded
                            ? diffSegments(bounds.table1, bounds.table2, infoTreeRecorder, bounds.maxRows, 0)
                            : diffSingleSegment(table1, table2, infoTreeRecorder, 0L, 0));
        }
        if (shouldSegmentJoin(table1, table2, level)) {
            return segmentJoinAndDiff(table1, table2, infoTreeRecorder, maxRows, level);
        }
        return diffSingleSegment(table1, table2, infoTreeRecorder, maxRows, level);
    }

    // ------------------------------------------------------------------
    // P2-2: join 分段
    // ------------------------------------------------------------------

    private static final int JOIN_MAX_DEPTH = 10;

    /** 根段无边界时先取 count+min/max，再交给分段器（与 checksum 的根段处理一致）。 */
    private CompletableFuture<JoinBounds> establishJoinBounds(TableSegment table1, TableSegment table2) {
        return CompletableFuture.supplyAsync(() -> {
            TableSegment.ChecksumResult bounds1;
            TableSegment.ChecksumResult bounds2;
            try {
                bounds1 = table1.countAndBounds();
                bounds2 = table2.countAndBounds();
            } catch (RuntimeException e) {
                // 方言不支持 count+min/max 时不强行分段：退回"单段一条 join"，行为与旧版一致。
                log.warn("JoinDiff cannot establish key bounds; keeping single-pass join: {}", e.getMessage());
                return JoinBounds.unbounded(table1, table2);
            }
            if (bounds1 == null || bounds2 == null) {
                log.warn("JoinDiff key bounds unavailable; keeping single-pass join");
                return JoinBounds.unbounded(table1, table2);
            }
            if (TableDiffer.mayContainNullKeys(table1) || TableDiffer.mayContainNullKeys(table2)) {
                // 区间谓词对 NULL 行恒为 UNKNOWN：可空键存在 NULL 时先不分段，
                // 保持单条 join（至少不会把 NULL 键行排除在所有分段之外）。
                log.warn("JoinDiff nullable keys present; keeping single-pass join so NULL keys stay in scope");
                return JoinBounds.unbounded(table1, table2);
            }
            TableSegment bounded1 = withBounds(table1, bounds1.getMinKey(), bounds1.getMaxKey(), true);
            TableSegment bounded2 = withBounds(table2, bounds2.getMinKey(), bounds2.getMaxKey(), true);
            long maxRows = Math.max(bounds1.getCount(), bounds2.getCount());
            // 空表或整侧被过滤掉时 MIN/MAX 为空，段依然"无边界"；此时必须回退单段，
            // 否则 diffSegments 会在 level=0 反复进入本方法（无限递归 + 反复发 COUNT/边界查询）。
            if (!bounded1.isBounded() || !bounded2.isBounded()) {
                log.warn("JoinDiff key bounds unusable (empty or fully filtered side); "
                        + "keeping single-pass join");
                return JoinBounds.unbounded(table1, table2);
            }
            log.info("JoinDiff bounds: table1 count={} min={} max={}, table2 count={} min={} max={}",
                    bounds1.getCount(), bounds1.getMinKey(), bounds1.getMaxKey(),
                    bounds2.getCount(), bounds2.getMinKey(), bounds2.getMaxKey());
            return new JoinBounds(bounded1, bounded2, maxRows, true);
        }, executorProvider.getIoExecutor());
    }

    private boolean shouldSegmentJoin(TableSegment table1, TableSegment table2, int level) {
        if (!table1.isBounded() || !table2.isBounded()) {
            return false;
        }
        int depthLimit = Math.min(JOIN_MAX_DEPTH, config.getMaxDepth() + 8);
        if (level >= depthLimit) {
            return false;
        }
        long rows = Math.max(segmentRows(table1), segmentRows(table2));
        return rows > config.getBisectionThreshold();
    }

    private CompletableFuture<Void> segmentJoinAndDiff(TableSegment table1, TableSegment table2,
            InfoTreeRecorder infoTreeRecorder, long maxRows, int level) {
        TableSegment base = unionKeySpace(table1, table2);
        int factor = Math.max(2, Math.min(config.getBisectionFactor(), 32));
        TableSegmenter segmenter = new TableSegmenter(base.getDatabase(),
                new TableSegmenter.SegmenterConfig(config.getBisectionThreshold(), 1000, factor),
                executorProvider);
        return segmenter.createOptimalSegments(base, factor, config.getBisectionThreshold())
                .thenCompose(segments -> {
                    if (segments == null || segments.size() <= 1) {
                        return diffSingleSegment(table1, table2, infoTreeRecorder, maxRows, level);
                    }
                    log.info("JoinDiff segmenting {} <-> {} into {} slices at level {}",
                            table1.getTablePath(), table2.getTablePath(), segments.size(), level);
                    List<CompletableFuture<Void>> futures = new ArrayList<>(segments.size());
                    long perSegmentRows = Math.max(1L, maxRows / segments.size());
                    for (TableSegment slice : segments) {
                        // 切片来自并集基座（table1 的副本）。两侧可能共用同一个 DatabaseAdapter
                        // 实例，不能按 adapter 身份判断归属，否则会把 table1 的切片当成 table2，
                        // 变成 table1 自连接。按表路径 + 过滤条件判断。
                        TableSegment slice1 = sameSource(slice, table1)
                                ? slice : withBounds(table1, slice.getMinKey().orElse(null),
                                        slice.getMaxKey().orElse(null), slice.isUpperBoundInclusive());
                        TableSegment slice2 = sameSource(slice, table2)
                                ? slice : withBounds(table2, slice.getMinKey().orElse(null),
                                        slice.getMaxKey().orElse(null), slice.isUpperBoundInclusive());
                        futures.add(diffSegments(slice1, slice2, infoTreeRecorder, perSegmentRows, level + 1));
                    }
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                });
    }

    private static boolean sameSource(TableSegment slice, TableSegment table) {
        return slice.getTablePath().equals(table.getTablePath())
                && slice.getWhereClause().equals(table.getWhereClause());
    }

    private static TableSegment withBounds(TableSegment segment, List<Object> minKey, List<Object> maxKey,
            boolean upperInclusive) {
        return segment.toBuilder()
                .minKey(Optional.ofNullable(minKey))
                .maxKey(Optional.ofNullable(maxKey))
                .upperBoundInclusive(upperInclusive)
                .build();
    }

    /** 分段基座：两侧键范围的分量级并集，保证任一侧的行都落在某个分段内。 */
    private static TableSegment unionKeySpace(TableSegment table1, TableSegment table2) {
        List<Object> min1 = table1.getMinKey().orElse(null);
        List<Object> min2 = table2.getMinKey().orElse(null);
        List<Object> max1 = table1.getMaxKey().orElse(null);
        List<Object> max2 = table2.getMaxKey().orElse(null);
        List<Object> unionMin = componentExtreme(min1, min2, true);
        List<Object> unionMax = componentExtreme(max1, max2, false);
        return table1.toBuilder()
                .minKey(Optional.ofNullable(unionMin))
                .maxKey(Optional.ofNullable(unionMax))
                .upperBoundInclusive(true)
                .build();
    }

    static List<Object> componentExtreme(List<Object> left, List<Object> right, boolean wantMin) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        int dimensions = Math.min(left.size(), right.size());
        List<Object> result = new ArrayList<>(Math.max(left.size(), right.size()));
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            Object l = i < left.size() ? left.get(i) : null;
            Object r = i < right.size() ? right.get(i) : null;
            if (l == null || r == null) {
                // NULL 是最小值：求并集下界时任一列为 NULL 就必须保留 NULL，
                // 否则会从所有分段中漏掉该侧 NULL 键行；上界则取非 NULL 的一侧。
                result.add(wantMin ? null : (l != null ? l : r));
                continue;
            }
            int cmp = compareKeyValues(l, r);
            result.add(wantMin ? (cmp <= 0 ? l : r) : (cmp >= 0 ? l : r));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeyValues(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString()));
        }
        if (left.getClass().equals(right.getClass()) && left instanceof Comparable) {
            return ((Comparable) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private long segmentRows(TableSegment segment) {
        long size = segment.approximateSize();
        if (size >= 0) {
            return size;
        }
        try {
            return segment.count();
        } catch (Exception e) {
            log.debug("Failed to estimate join segment rows for {}, assuming threshold", segment.getTablePath(), e);
            return config.getBisectionThreshold();
        }
    }

    private static final class JoinBounds {
        private final TableSegment table1;
        private final TableSegment table2;
        private final long maxRows;
        /** false 表示边界不可得，调用方必须直接走单段 join，不能再次进入本方法（否则递归）。 */
        private final boolean bounded;

        private JoinBounds(TableSegment table1, TableSegment table2, long maxRows, boolean bounded) {
            this.table1 = table1;
            this.table2 = table2;
            this.maxRows = maxRows;
            this.bounded = bounded;
        }

        private static JoinBounds unbounded(TableSegment table1, TableSegment table2) {
            return new JoinBounds(table1, table2, 0L, false);
        }
    }

    private CompletableFuture<Void> diffSingleSegment(
            TableSegment table1,
            TableSegment table2,
            InfoTreeRecorder infoTreeRecorder,
            long maxRows,
            int level) {

        String segmentId = generateSegmentId(table1, table2, level);
        performanceMonitor.startJoin(segmentId);
        infoTreeRecorder.startNode(segmentId, "joindiff", "segment", "segment", segmentId, level);
        infoTreeRecorder.recordRange(segmentId,
                table1.getMinKey().map(Object::toString).orElse(null),
                table1.getMaxKey().map(Object::toString).orElse(null),
                table1.getWhereClause().orElse(null));
        infoTreeRecorder.recordMetric(segmentId, "maxRows", maxRows);
        infoTreeRecorder.recordDecision(segmentId, "join_diff");

        log.info("JoinDiffing segment {} at level {}, size: {}", segmentId, level, maxRows);

        DatabaseAdapter database = table1.getDatabase();

        // Build segment-specific join query
        JoinQueryPlan segmentPlan = buildSegmentJoinQueryPlan(table1, table2, level);

        // Execute all join operations in parallel
        // Execute all supporting queries alongside the main diff query so the segment finishes in one pass.
        CompletableFuture<long[]> statsFuture = collectStatistics(database, segmentPlan, segmentId, infoTreeRecorder);

        // Main join execution
        CompletableFuture<List<Object[]>> diffFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return database.query(segmentPlan.getMainDiffQuery(), Object[].class);
                    } catch (Exception e) {
                        log.error("Error executing main diff query for segment: {}", segmentId, e);
                        throw new RuntimeException("Main diff query failed", e);
                    }
                }, executorProvider.getIoExecutor());

        return CompletableFuture
                .allOf(statsFuture, diffFuture)
                .thenAccept(v -> {
                    try {
                        // Consume the join output once all auxiliary futures succeeded.
                        List<Object[]> differences = diffFuture.get();
                        int diffCount = processDiffResults(differences, segmentPlan, segmentId, infoTreeRecorder);
                        infoTreeRecorder.addRowsFetched(segmentId, differences.size());
                        infoTreeRecorder.addQueryCount(segmentId, 1);
                        // 预算要按"扫过的行数"累计：join 输出的差异行数在内容一致的大表上是 0，
                        // 永远触发不了 maxRowsScanned。statsFuture 里是两侧真实行数。
                        long[] counts = statsFuture.getNow(new long[]{0L, 0L});
                        budgetGuard.addRowsScanned(Math.max(0L, counts[0]) + Math.max(0L, counts[1]));
                        performanceMonitor.recordSegmentResults(segmentId, diffCount);
                    } catch (Exception e) {
                        if (e instanceof com.consilens.core.exception.BudgetExceededException) {
                            throw (com.consilens.core.exception.BudgetExceededException) e;
                        }
                        log.error("Failed to process diff results for segment: {}", segmentId, e);
                        throw new RuntimeException("Failed to process diff results", e);
                    } finally {
                        performanceMonitor.endJoin(segmentId);
                        infoTreeRecorder.endNode(segmentId);
                    }
                });
    }

    /**
     * 开放档的重复键划分：探测两侧重复键；命中则给出
     * ① join 用的 where（收窄到"两侧键都唯一"的子集）
     * ② 多重集配对用的 where（"任一侧键重复"的行）。
     */
    private DupKeySplit prepareDupKeySplit(TableSegment table1, TableSegment table2) {
        DatabaseAdapter database = table1.getDatabase();
        List<String> sourceViolations = probeDuplicateKeys(table1);
        List<String> targetViolations = probeDuplicateKeys(table2);
        if (sourceViolations.isEmpty() && targetViolations.isEmpty()) {
            return DupKeySplit.none();
        }
        var generator = database.getSqlQueryGenerator();
        if (!generator.supportsKeyMultiplicityFilter()) {
            log.info("JoinDiff 开放档：检测到重复键且该方言不支持键多重性过滤，整表退化为本地多重集配对"
                            + "（与 checksum/streaming 同一实现）\n  source: {}\n  target: {}",
                    String.join("; ", sourceViolations), String.join("; ", targetViolations));
            return DupKeySplit.localOnlySplit();
        }
        log.info("JoinDiff 开放档：检测到重复键，这些键改走多重集配对（避免 FULL OUTER JOIN 的 N×M 放大）"
                        + "\n  source: {}\n  target: {}",
                String.join("; ", sourceViolations), String.join("; ", targetViolations));
        String schema1 = table1.getTablePath().getSchema().orElse(null);
        String tableName1 = table1.getTablePath().getTableName();
        String schema2 = table2.getTablePath().getSchema().orElse(null);
        String tableName2 = table2.getTablePath().getTableName();
        Map<String, DataType> types1 = extractColumnTypes(table1);
        Map<String, DataType> types2 = extractColumnTypes(table2);

        // 谓词不能在这里一次性生成：分段后每段的键范围不同，且 join 会把 where 放进
        // 带别名的派生表。DupKeySplit 保留表/键/类型元数据，按当前 segment 现算。
        return new DupKeySplit(true, false, database,
                schema1, tableName1, table1.getKeyColumns(), types1,
                schema2, tableName2, table2.getKeyColumns(), types2);
    }

    /**
     * 开放档探针：失败不阻断比对（唯一键校验在开放档不参与正确性保证），
     * 但必须留下告警，避免"探针没跑成"被静默当成"没有重复键"。
     */
    private List<String> probeDuplicateKeys(TableSegment table) {
        try {
            return table.getDatabase().findDuplicateKeys(table);
        } catch (Exception e) {
            log.warn("JoinDiff 开放档：重复键探针执行失败，按无重复键继续比对（不阻断）: {} - {}",
                    table.getTablePath(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 整表本地多重集配对（方言不支持键多重性过滤时的开放档回退）：
     * 结果与 checksum/streaming 逐数字一致，只是不做 SQL 下推。
     */
    private void compareWholeTableLocally(TableSegment table1, TableSegment table2,
            InfoTreeRecorder infoTreeRecorder) {
        List<Object[]> rows1 = table1.getValues();
        List<Object[]> rows2 = table2.getValues();
        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                rows1, rows2,
                table1.getKeyColumns(), table1.getExtraColumns(),
                table2.getKeyColumns(), table2.getExtraColumns(),
                extractColumnTypes(table1), extractColumnTypes(table2),
                dataTypeHandler(table1), dataTypeHandler(table2));
        long sourceMissingDelta = 0;
        long targetMissingDelta = 0;
        long mismatchDelta = 0;
        for (DiffRow row : differences) {
            if (row.getOperation() == DiffOperation.SOURCE_MISSING) {
                sourceMissingCount.incrementAndGet();
                sourceMissingDelta++;
            } else if (row.getOperation() == DiffOperation.TARGET_MISSING) {
                targetMissingCount.incrementAndGet();
                targetMissingDelta++;
            } else if (row.getOperation() == DiffOperation.MISMATCH) {
                mismatchCount.incrementAndGet();
                mismatchDelta++;
            }
            emitDiffRow(row);
        }
        infoTreeRecorder.addDiffCounts("joindiff-local-only", differences.size(),
                sourceMissingDelta, targetMissingDelta, mismatchDelta);
        log.info("JoinDiff 开放档（方言不支持过滤器）：整表本地多重集配对 {} 行 vs {} 行 → {} 条差异",
                rows1.size(), rows2.size(), differences.size());
    }

    /**
     * 重复键子集：拉取两侧行做多重集配对（值相等的行抵消 → 剩余按签名排序配对 → 多余行 SM/TM），
     * 与 checksum/streaming 共用 {@link LocalDiffEngine}，保证跨计划结果逐数字一致。
     */
    private void compareDuplicateKeyRows(DupKeySplit split, TableSegment table1, TableSegment table2,
            InfoTreeRecorder infoTreeRecorder) {
        // 谓词含 EXISTS/子查询，属引擎构造（用户 filter 已在 segment.buildWhereClause 内校验过），
        // 因此走 querySegmentWithPredicate 而不是塞进 segment 的自定义 where。
        List<Object[]> rows1 = table1.getDatabase().querySegmentWithPredicate(
                table1, split.dupFetchWhere(table1, table2, true));
        List<Object[]> rows2 = table2.getDatabase().querySegmentWithPredicate(
                table2, split.dupFetchWhere(table1, table2, false));
        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                rows1, rows2,
                table1.getKeyColumns(), table1.getExtraColumns(),
                table2.getKeyColumns(), table2.getExtraColumns(),
                extractColumnTypes(table1), extractColumnTypes(table2),
                dataTypeHandler(table1), dataTypeHandler(table2));
        long sourceMissingDelta = 0;
        long targetMissingDelta = 0;
        long mismatchDelta = 0;
        for (DiffRow row : differences) {
            if (row.getOperation() == DiffOperation.SOURCE_MISSING) {
                sourceMissingCount.incrementAndGet();
                sourceMissingDelta++;
            } else if (row.getOperation() == DiffOperation.TARGET_MISSING) {
                targetMissingCount.incrementAndGet();
                targetMissingDelta++;
            } else if (row.getOperation() == DiffOperation.MISMATCH) {
                mismatchCount.incrementAndGet();
                mismatchDelta++;
            }
            emitDiffRow(row);
        }
        infoTreeRecorder.addDiffCounts("joindiff-dup-keys", differences.size(),
                sourceMissingDelta, targetMissingDelta, mismatchDelta);
        log.info("JoinDiff 开放档：重复键子集 {} 行 vs {} 行 → {} 条差异（多重集配对）",
                rows1.size(), rows2.size(), differences.size());
    }

    /**
     * 开放档下重复键的划分谓词。
     *
     * <p>只保留表/键/类型元数据；多重性谓词在 {@link #joinWhere}/{@link #dupFetchWhere}
     * 中按当前 segment 的键范围现算，避免分段后仍用根段的全表条件。
     */
    private static final class DupKeySplit {
        private final boolean duplicates;
        /** 方言不支持键多重性过滤器：整表走本地多重集配对（不做 SQL join）。 */
        private final boolean localOnly;
        private final DatabaseAdapter database;
        private final String schema1;
        private final String tableName1;
        private final List<String> keys1;
        private final Map<String, DataType> types1;
        private final String schema2;
        private final String tableName2;
        private final List<String> keys2;
        private final Map<String, DataType> types2;

        private DupKeySplit(boolean duplicates, boolean localOnly, DatabaseAdapter database,
                String schema1, String tableName1, List<String> keys1, Map<String, DataType> types1,
                String schema2, String tableName2, List<String> keys2, Map<String, DataType> types2) {
            this.duplicates = duplicates;
            this.localOnly = localOnly;
            this.database = database;
            this.schema1 = schema1;
            this.tableName1 = tableName1;
            this.keys1 = keys1;
            this.types1 = types1;
            this.schema2 = schema2;
            this.tableName2 = tableName2;
            this.keys2 = keys2;
            this.types2 = types2;
        }

        private static DupKeySplit none() {
            return new DupKeySplit(false, false, null,
                    null, null, null, null, null, null, null, null);
        }

        /** 方言不支持过滤器时的划分（整表本地配对）。 */
        private static DupKeySplit localOnlySplit() {
            return new DupKeySplit(true, true, null,
                    null, null, null, null, null, null, null, null);
        }

        private boolean hasDuplicates() {
            return duplicates;
        }

        private boolean localOnly() {
            return localOnly;
        }

        /** join 的 where：本侧键唯一 且 对侧键不重复（对侧重复的键由多重集配对统一处理）。 */
        private String joinWhere(TableSegment table1, TableSegment table2, boolean side1) {
            String base = side1 ? table1.buildWhereClause() : table2.buildWhereClause();
            if (localOnly || !duplicates) {
                return base;
            }
            // 谓词按当前 segment 现算：必须包含本段键范围，否则每个分段都会扫全表并重复报差异
            String selfUnique = multiplicityPredicate(side1, true, table1, table2, false);
            String otherDup = multiplicityPredicate(side1, false, table1, table2, true);
            return combine(base, selfUnique, "NOT (" + otherDup + ")");
        }

        /** 多重集配对的行范围：本侧键重复 或 对侧键重复（对侧重复的键需要本侧行一起配对）。 */
        private String dupFetchWhere(TableSegment table1, TableSegment table2, boolean side1) {
            String base = side1 ? table1.buildWhereClause() : table2.buildWhereClause();
            String selfDup = multiplicityPredicate(side1, true, table1, table2, true);
            String otherDup = multiplicityPredicate(side1, false, table1, table2, true);
            return combine(base, "(" + selfDup + " OR " + otherDup + ")");
        }

        /**
         * @param self  true=检查本侧表的键重复；false=检查对侧表的键是否重复
         * @param outerQualifier 外层行所属表名（本侧），与 where 所处的查询作用域一致
         */
        private String multiplicityPredicate(boolean side1, boolean self,
                TableSegment table1, TableSegment table2, boolean duplicates) {
            TableSegment inner = self ? (side1 ? table1 : table2) : (side1 ? table2 : table1);
            String schema = self ? (side1 ? schema1 : schema2) : (side1 ? schema2 : schema1);
            String tableName = self ? (side1 ? tableName1 : tableName2) : (side1 ? tableName2 : tableName1);
            List<String> keys = self ? (side1 ? keys1 : keys2) : (side1 ? keys2 : keys1);
            Map<String, DataType> types = self ? (side1 ? types1 : types2) : (side1 ? types2 : types1);
            String outerQualifier = side1 ? tableName1 : tableName2;
            return database.getSqlQueryGenerator().getKeyMultiplicityFilterSQL(
                    schema, tableName, keys, types, inner.buildWhereClause(), duplicates, outerQualifier);
        }

        private static String combine(String... parts) {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" AND ");
                }
                sb.append("(").append(part).append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Build the main join query plan.
     */
    private JoinQueryPlan buildJoinQueryPlan(TableSegment table1, TableSegment table2,
                                             String alias1, String alias2,
                                             String where1, String where2) {
        List<String> keyColumns1 = table1.getKeyColumns();
        List<String> keyColumns2 = table2.getKeyColumns();
        List<String> relevantColumns1 = table1.getRelevantColumns();
        List<String> relevantColumns2 = table2.getRelevantColumns();
        List<String> compareColumns1 = table1.getExtraColumns();
        List<String> compareColumns2 = table2.getExtraColumns();

        String schema1 = table1.getTablePath().getSchema().orElse(null);
        String tableName1 = table1.getTablePath().getTableName();
        String schema2 = table2.getTablePath().getSchema().orElse(null);
        String tableName2 = table2.getTablePath().getTableName();

        var generator = table1.getDatabase().getSqlQueryGenerator();

        // 比较列必须按方言归一化口径比较（与 checksum/local 路径一致），否则会出现
        // "窗口内差异被报出（多报）+ 库内排序规则差异被吸收（漏报）"的双向偏差。
        Map<String, DataType> columnTypes1 = extractColumnTypes(table1);
        Map<String, DataType> columnTypes2 = extractColumnTypes(table2);

        String mainQuery = generator.getJoinDiffDetailSQL(
                schema1, tableName1, alias1,
                keyColumns1, compareColumns1, relevantColumns1, where1, columnTypes1,
                schema2, tableName2, alias2,
                keyColumns2, compareColumns2, relevantColumns2, where2, columnTypes2);

        log.debug("Main join query: {}", mainQuery);

        String countQuery = generator.getJoinDiffStatsSQL(
                schema1, tableName1, alias1,
                keyColumns1, compareColumns1, where1, columnTypes1,
                schema2, tableName2, alias2,
                keyColumns2, compareColumns2, where2, columnTypes2);

        return new JoinQueryPlan(mainQuery, countQuery,
                keyColumns1, keyColumns2, relevantColumns1, relevantColumns2,
                compareColumns1, compareColumns2);
    }

    /**
     * 本地比较使用的值归一化器：由方言决定驱动特定类型（如 MySQL YEAR）的比较形态。
     */
    private DataTypeHandler dataTypeHandler(TableSegment segment) {
        if (segment == null || segment.getDatabase() == null || segment.getDatabase().getDialect() == null) {
            return null;
        }
        return segment.getDatabase().getDialect().getDataTypeHandler();
    }

    /**
     * 取段内相关列的归一化类型；schema 不可用时返回空表（比较退化为原始列值）。
     */
    private Map<String, DataType> extractColumnTypes(TableSegment segment) {
        Map<String, DataType> types = new HashMap<>();
        Optional<TableSchema> schema = segment.getSchema();
        if (!schema.isPresent()) {
            return types;
        }
        for (String column : segment.getRelevantColumns()) {
            DataType type = schema.get().getColumnType(column);
            if (type != null) {
                types.put(column, type);
            }
        }
        return types;
    }

    /**
     * Collect statistics.
     */
    private CompletableFuture<long[]> collectStatistics(DatabaseAdapter database, JoinQueryPlan queryPlan,
            String segmentId, InfoTreeRecorder infoTreeRecorder) {
        return CompletableFuture.supplyAsync(() -> {
            long[] counts = new long[]{0L, 0L};
            try {
                List<Object[]> stats = database.query(queryPlan.getCountQuery(), Object[].class);
                if (!stats.isEmpty()) {
                    Object[] statRow = stats.get(0);
                    performanceMonitor.recordStatistics(segmentId, statRow);
                    Long statsSource = statRow.length >= 5 ? toCount(statRow[0]) : null;
                    Long statsTarget = statRow.length >= 5 ? toCount(statRow[1]) : null;
                    if (statRow.length >= 5
                            && statsSource != null && statsTarget != null
                            && toCount(statRow[2]) != null
                            && toCount(statRow[3]) != null
                            && toCount(statRow[4]) != null) {
                        long sourceCount = statsSource;
                        long targetCount = statsTarget;
                        counts[0] = sourceCount;
                        counts[1] = targetCount;
                        infoTreeRecorder.recordCounts(segmentId, sourceCount, targetCount);
                        // 差异计数只由 processDiffResults（明细行）写入，与 ChecksumDiffer 一致。
                        // 这里再写一次会让同一段的 diffCount/totalDifferences 翻倍：
                        // 统计 SQL 与明细 SQL 描述的是同一批差异。
                        long sourceMissing = toCount(statRow[2]);
                        long targetMissing = toCount(statRow[3]);
                        long mismatch = toCount(statRow[4]);
                        log.debug("Join segments stats for {}: sourceMissing={}, targetMissing={}, mismatch={}",
                                segmentId, sourceMissing, targetMissing, mismatch);
                    } else {
                        // 统计行存在但取不出 5 个数字（例如 SUM 全为 NULL、驱动给出非数值类型）：
                        // 静默保留 {0,0} 会让"行数预算"永远累计 0、匹配分的分母变 0，
                        // 报告会给出"100 分 / 无差异"。这种情况必须显式失败。
                        throw new IllegalStateException("Row statistics for segment " + segmentId
                                + " could not be read as five numbers (got "
                                + java.util.Arrays.toString(statRow)
                                + " of types " + java.util.Arrays.stream(statRow)
                                        .map(v -> v == null ? "null" : v.getClass().getSimpleName())
                                        .collect(java.util.stream.Collectors.joining(","))
                                + "); row counts and the match score would be meaningless");
                    }
                }
            } catch (Exception e) {
                // 不能吞成 0：行数统计是报告里"差异占比/匹配分"的分母，0 行会被读成
                // "两侧都空、100 分 EXCELLENT"。这里必须让本次比对按失败结束，并说清是哪一侧。
                throw new IllegalStateException("Failed to count rows for segment " + segmentId
                        + "; row counts and the match score would be meaningless without them", e);
            }
            return counts;
        }, executorProvider.getIoExecutor());
    }

    /**
     * 统计行的数值转换。
     *
     * <p>不能只认 {@code instanceof Number}：MySQL 的 {@code SUM()} 经驱动/适配层后可能拿到
     * 字符串形态的数字，早先这种行会被整段丢弃，行数停在 {@code {0,0}}——于是
     * ① 行数预算永远累计 0（join 模式下 maxRowsScanned 形同不存在）；
     * ② 匹配分的分母变 0，报告给出"100 分 / 无差异"。
     */
    /**
     * 统计行取数：SQL 的 {@code SUM()} 在空结果集上返回 {@code NULL}，这表示"没有行"而不是"读不懂"，
     * 因此按 0 处理（两侧都是空表时应当得出 0 处差异，而不是失败）。
     *
     * <p>只有"有值但解析不出来"才返回 {@code null}，交给调用方按统计不可用失败。
     */
    static Long toCount(Object value) {
        // 注意三元里不要混 long/Long：long 分支会让另一侧自动拆箱，toLong 返回 null 时直接 NPE
        return value == null ? Long.valueOf(0L) : toLong(value);
    }

    static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return 0L;
            }
            try {
                return new java.math.BigDecimal(text).longValue();
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /**
     * Process diff results.
     */
    private int processDiffResults(List<Object[]> differences, JoinQueryPlan queryPlan, String segmentId,
            InfoTreeRecorder infoTreeRecorder) {
        if (differences != null && !differences.isEmpty()) {
            infoTreeRecorder.markFirstDifference();
        }
        int diffCount = 0;
        long sourceMissingDelta = 0;
        long targetMissingDelta = 0;
        long mismatchDelta = 0;

        // Create column names lists for each table
        List<String> columnNames1 = new ArrayList<>(queryPlan.getRelevantColumns1());
        List<String> columnNames2 = new ArrayList<>(queryPlan.getRelevantColumns2());

        log.debug(
                "Processing {} diff results for segment {}. columnNames1={}, columnNames2={}",
                differences.size(), segmentId, columnNames1, columnNames2);

        for (int i = 0; i < differences.size(); i++) {
            Object[] row = differences.get(i);
            // Expected format:
            // [diff_type, diff_columns1, diff_columns2, t1_col1..t1_colN, t2_col1..t2_colM]
            String diffType = row.length > 0 && row[0] != null ? row[0].toString().toLowerCase(Locale.ROOT) : "";
            Object diffColumns1Raw = row.length > 1 ? row[1] : null;
            Object diffColumns2Raw = row.length > 2 ? row[2] : null;

            if (i < 1) { // Log first 1 row with full details
                log.debug("Row {}: length={}, diffType={}, values={}", i, row.length, diffType, Arrays.toString(row));
            }

            int offset = 3;
            List<Object> values1 = extractValues(row, offset, columnNames1.size());
            List<Object> values2 = extractValues(row, offset + columnNames1.size(), columnNames2.size());

            if ("source_missing".equals(diffType)) {
                List<Object> primaryKey = extractPrimaryKey(queryPlan.getKeyColumns2(), columnNames2, values2);
                DiffRow diffRow = DiffRow.builder()
                        .operation(DiffOperation.SOURCE_MISSING)
                        .primaryKey(primaryKey)
                        .sourceValues(Optional.empty())
                        .targetValues(Optional.of(values2))
                        .columnNames1(Collections.emptyList())
                        .columnNames2(new ArrayList<>(columnNames2))
                        .metadata(new HashMap<>())
                        .build();
                sourceMissingCount.incrementAndGet();
                sourceMissingDelta++;
                diffCount++;
                emitDiffRow(diffRow);
            } else if ("target_missing".equals(diffType)) {
                List<Object> primaryKey = extractPrimaryKey(queryPlan.getKeyColumns1(), columnNames1, values1);
                DiffRow diffRow = DiffRow.builder()
                        .operation(DiffOperation.TARGET_MISSING)
                        .primaryKey(primaryKey)
                        .sourceValues(Optional.of(values1))
                        .targetValues(Optional.empty())
                        .columnNames1(new ArrayList<>(columnNames1))
                        .columnNames2(Collections.emptyList())
                        .metadata(new HashMap<>())
                        .build();
                targetMissingCount.incrementAndGet();
                targetMissingDelta++;
                diffCount++;
                emitDiffRow(diffRow);
            } else if ("mismatch".equals(diffType)) {
                List<Object> primaryKey = extractPrimaryKey(queryPlan.getKeyColumns1(), columnNames1, values1);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("changedColumns1", parseDiffColumns(diffColumns1Raw));
                metadata.put("changedColumns2", parseDiffColumns(diffColumns2Raw));

                DiffRow diffRow = DiffRow.builder()
                        .operation(DiffOperation.MISMATCH)
                        .primaryKey(primaryKey)
                        .sourceValues(Optional.of(values1))
                        .targetValues(Optional.of(values2))
                        .columnNames1(new ArrayList<>(columnNames1))
                        .columnNames2(new ArrayList<>(columnNames2))
                        .metadata(metadata)
                        .build();
                mismatchCount.incrementAndGet();
                mismatchDelta++;
                diffCount++;
                emitDiffRow(diffRow);
            }
        }

        log.info("JoinDiff found {} differences for segment: {}", diffCount, segmentId);
        infoTreeRecorder.addDiffCounts(segmentId, diffCount,
                sourceMissingDelta, targetMissingDelta, mismatchDelta);
        return diffCount;
    }

    /**
     * Extract values from result row.
     */
    private List<Object> extractValues(Object[] row, int startIndex, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            values.add(idx < row.length ? row[idx] : null);
        }
        return values;
    }

    private List<Object> extractPrimaryKey(List<String> keyColumns, List<String> columnNames,
                                           List<Object> values) {
        List<Object> keyValues = new ArrayList<>(keyColumns.size());
        for (String key : keyColumns) {
            int idx = columnNames.indexOf(key);
            if (idx >= 0 && idx < values.size()) {
                keyValues.add(values.get(idx));
            } else {
                keyValues.add(null);
            }
        }
        return keyValues;
    }

    private List<String> parseDiffColumns(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        String text = raw.toString().trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return new ArrayList<>();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return new ArrayList<>();
        }
        String[] parts = text.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String cleaned = part.trim();
            if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                    || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    /**
     * Build segment-specific join query plan.
     */
    private JoinQueryPlan buildSegmentJoinQueryPlan(
            TableSegment table1, TableSegment table2, int level) {
        String segmentAlias1 = "t1_l" + level;
        String segmentAlias2 = "t2_l" + level;
        DupKeySplit split = activeSplit;
        return buildJoinQueryPlan(table1, table2, segmentAlias1, segmentAlias2,
                split.joinWhere(table1, table2, true), split.joinWhere(table1, table2, false));
    }

    /**
     * Create final diff result.
     */
    private DiffResult createDiffResult(TableSegment table1, TableSegment table2, Void result, long sourceRowCount,
            long targetRowCount) {
        // Create comprehensive diff result with actual diff rows and statistics
        long sourceMissing = sourceMissingCount.get();
        long targetMissing = targetMissingCount.get();
        long mismatched = mismatchCount.get();
        long totalDifferences = sourceMissing + targetMissing + mismatched;

        DiffResult.DiffStatistics diffStatistics = DiffResult.DiffStatistics.builder()
                .sourceRowCount(sourceRowCount)
                .targetRowCount(targetRowCount)
                .sourceMissingCount(sourceMissing)
                .targetMissingCount(targetMissing)
                .mismatchCount(mismatched)
                .totalDifferences(totalDifferences)
                .processingTimeMs((Long) performanceMonitor.getMetrics().getOrDefault("root_duration", 0L))
                .unchangedCount(Math.max(0, Math.min(sourceRowCount, targetRowCount) - mismatched))
                .build();

        completeDiff(diffStatistics);

        // Create result with actual differences using DiffResult.builder for proper row
        // count statistics
        return DiffResult.builder()
                .differences(getCollectedDifferences())
                .statistics(diffStatistics)
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
        String base = String.format("join_L%d_%s_vs_%s", level,
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
     * Join query plan containing all related queries.
     */
    @Getter
    private static class JoinQueryPlan {
        // Getters
        private final String mainDiffQuery;
        private final String countQuery;
        private final List<String> keyColumns1;
        private final List<String> keyColumns2;
        private final List<String> relevantColumns1;
        private final List<String> relevantColumns2;
        private final List<String> compareColumns1;
        private final List<String> compareColumns2;

        public JoinQueryPlan(String mainDiffQuery, String countQuery,
                             List<String> keyColumns1, List<String> keyColumns2,
                             List<String> relevantColumns1, List<String> relevantColumns2,
                             List<String> compareColumns1, List<String> compareColumns2) {
            this.mainDiffQuery = mainDiffQuery;
            this.countQuery = countQuery;
            this.keyColumns1 = keyColumns1;
            this.keyColumns2 = keyColumns2;
            this.relevantColumns1 = relevantColumns1;
            this.relevantColumns2 = relevantColumns2;
            this.compareColumns1 = compareColumns1;
            this.compareColumns2 = compareColumns2;
        }

    }

    /**
     * Enhanced options for JoinDiff.
     */
    @Getter
    public static class JoinDifferOptions {
        private final boolean validateUniqueKeys;

        public JoinDifferOptions(boolean validateUniqueKeys) {
            this.validateUniqueKeys = validateUniqueKeys;
        }

    }

    /**
     * Performance monitor for JoinDiff operations.
     */
    private static class JoinPerformanceMonitor {
        private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
        private final Map<String, Object> metrics = new ConcurrentHashMap<>();
        private final AtomicLong segmentCount = new AtomicLong(0);

        public void startJoin(String segmentId) {
            startTimes.put(segmentId, System.currentTimeMillis());
            segmentCount.incrementAndGet();
        }

        public void endJoin(String segmentId) {
            Long startTime = startTimes.remove(segmentId);
            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                metrics.put(segmentId + "_duration", duration);
            }
        }

        public void recordStage(String stage) {
            metrics.put(stage, System.currentTimeMillis());
        }

        public void recordStatistics(String segmentId, Object[] statRow) {
            if (statRow == null || statRow.length < 5) {
                return;
            }
            // ConcurrentHashMap 不接受 null：空表时 SUM() 全是 NULL，直接 put 会在 putVal 里 NPE
            //（表现为"两侧都是空表"的比对直接失败）。空值就是"没有数据"，跳过即可。
            putIfPresent(segmentId, "source_count", statRow[0]);
            putIfPresent(segmentId, "target_count", statRow[1]);
            putIfPresent(segmentId, "source_missing", statRow[2]);
            putIfPresent(segmentId, "target_missing", statRow[3]);
            putIfPresent(segmentId, "mismatch", statRow[4]);
        }

        private void putIfPresent(String segmentId, String name, Object value) {
            if (value != null) {
                metrics.put(segmentId + "_" + name, value);
            }
        }

        public void recordSegmentResults(String segmentId, int differenceCount) {
            metrics.put(segmentId + "_differences", differenceCount);
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

    private void validateSameDatabase(TableSegment table1, TableSegment table2) {
        String url1 = extractJdbcUrl(table1);
        String url2 = extractJdbcUrl(table2);

        if (url1 == null || url2 == null) {
            throw new IllegalArgumentException("Join strategy requires JDBC URL metadata for both tables");
        }

        if (!Objects.equals(url1, url2)) {
            throw new IllegalArgumentException(
                    String.format("Tables must be in the same database. Table1: %s, Table2: %s", url1, url2));
        }
    }

    private String extractJdbcUrl(TableSegment table) {
        if (table == null || table.getDatabase() == null) {
            return null;
        }
        if (table.getDatabase().getConnectionPool() == null
                || table.getDatabase().getConnectionPool().getConfiguration() == null) {
            return null;
        }
        return table.getDatabase().getConnectionPool().getConfiguration().getJdbcUrl();
    }

    private String safeDatabaseName(TableSegment table) {
        if (table == null || table.getDatabase() == null) {
            return null;
        }
        return table.getDatabase().getName();
    }


    @Override
    public void close() {
        try {
            performanceMonitor.clear();
            super.shutdown();
            log.info("EnhancedJoinDiffer closed successfully");
        } catch (Exception e) {
            log.error("Error closing EnhancedJoinDiffer", e);
        }
    }
}
