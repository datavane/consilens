package com.consilens.core.algorithm;

import com.consilens.core.segment.TableSegment;
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
    private final JoinPerformanceMonitor performanceMonitor;
    private final AtomicLong sourceMissingCount = new AtomicLong(0);
    private final AtomicLong targetMissingCount = new AtomicLong(0);
    private final AtomicLong mismatchCount = new AtomicLong(0);
    private final AtomicLong segmentSequence = new AtomicLong(0);

    public JoinDiffer(DifferConfig config, JoinDifferOptions options) {
        this(config, options, null);
    }

    public JoinDiffer(DifferConfig config, JoinDifferOptions options, ExecutorProvider executorProvider) {
        super(config,
                executorProvider != null ? executorProvider : new ExecutorProvider(config.getConcurrencyConfig()),
                executorProvider == null);
        this.validateUniqueKeys = options.isValidateUniqueKeys();
        this.performanceMonitor = new JoinPerformanceMonitor();
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

        // Run background validations and preparations
        // Kick off optional preflight jobs in parallel (key validation/materialization)
        CompletableFuture<Void> validationFuture = validateUniqueKeys ? performKeyValidation(table1, table2)
                : CompletableFuture.completedFuture(null);

        return CompletableFuture.allOf(validationFuture)
                .thenCompose(v -> {
                    performanceMonitor.recordStage("preparations_complete");

                    // Build the main join query
                    JoinQueryPlan queryPlan = buildJoinQueryPlan(table1, table2, "t1", "t2",
                            table1.buildWhereClause(), table2.buildWhereClause());
                    performanceMonitor.recordStage("query_plan_built");

                    // Execute the join diff
                    return executeJoinDiff(queryPlan, table1, table2, infoTreeRecorder)
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
                    log.error("JoinDiff failed", e);
                    throw new RuntimeException("JoinDiff failed", e);
                });
    }

    @Override
    protected CompletableFuture<Void> diffSegments(
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
        CompletableFuture<Void> statsFuture = collectStatistics(database, segmentPlan, segmentId, infoTreeRecorder);

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
                        performanceMonitor.recordSegmentResults(segmentId, diffCount);
                    } catch (Exception e) {
                        log.error("Failed to process diff results for segment: {}", segmentId, e);
                        throw new RuntimeException("Failed to process diff results", e);
                    } finally {
                        performanceMonitor.endJoin(segmentId);
                        infoTreeRecorder.endNode(segmentId);
                    }
                });
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

        String mainQuery = generator.getJoinDiffDetailSQL(
                schema1, tableName1, alias1,
                keyColumns1, compareColumns1, relevantColumns1, where1,
                schema2, tableName2, alias2,
                keyColumns2, compareColumns2, relevantColumns2, where2);

        log.debug("Main join query: {}", mainQuery);

        String countQuery = generator.getJoinDiffStatsSQL(
                schema1, tableName1, alias1,
                keyColumns1, compareColumns1, where1,
                schema2, tableName2, alias2,
                keyColumns2, compareColumns2, where2);

        return new JoinQueryPlan(mainQuery, countQuery,
                keyColumns1, keyColumns2, relevantColumns1, relevantColumns2,
                compareColumns1, compareColumns2);
    }

    /**
     * Build comprehensive join query for difference detection.
     */

    /**
     * Execute the join diff operation.
     */
    private CompletableFuture<Void> executeJoinDiff(
            JoinQueryPlan queryPlan, TableSegment table1, TableSegment table2, InfoTreeRecorder infoTreeRecorder) {

        return diffSegments(table1, table2, infoTreeRecorder, 0L, 0);
    }

    /**
     * Validate unique key constraints.
     */
    private CompletableFuture<Void> performKeyValidation(TableSegment table1, TableSegment table2) {
        return CompletableFuture.runAsync(() -> {
            log.debug("Validating unique keys for tables {} and {}",
                    table1.getTablePath(), table2.getTablePath());

            validateTableUniqueKeys(table1);
            validateTableUniqueKeys(table2);
        }, executorProvider.getIoExecutor());
    }

    /**
     * Validate unique keys for a single table.
     */
    private void validateTableUniqueKeys(TableSegment table) {
        // This would implement key validation logic
        // For now, just log the validation
        log.debug("Key validation for table: {}", table.getTablePath());
    }

    /**
     * Collect statistics.
     */
    private CompletableFuture<Void> collectStatistics(DatabaseAdapter database, JoinQueryPlan queryPlan,
            String segmentId, InfoTreeRecorder infoTreeRecorder) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Object[]> stats = database.query(queryPlan.getCountQuery(), Object[].class);
                if (!stats.isEmpty()) {
                    Object[] statRow = stats.get(0);
                    performanceMonitor.recordStatistics(segmentId, statRow);
                    if (statRow.length >= 5
                            && statRow[0] instanceof Number
                            && statRow[1] instanceof Number
                            && statRow[2] instanceof Number
                            && statRow[3] instanceof Number
                            && statRow[4] instanceof Number) {
                        long sourceCount = ((Number) statRow[0]).longValue();
                        long targetCount = ((Number) statRow[1]).longValue();
                        long sourceMissing = ((Number) statRow[2]).longValue();
                        long targetMissing = ((Number) statRow[3]).longValue();
                        long mismatch = ((Number) statRow[4]).longValue();
                        infoTreeRecorder.recordCounts(segmentId, sourceCount, targetCount);
                        infoTreeRecorder.addDiffCounts(segmentId,
                                sourceMissing + targetMissing + mismatch,
                                sourceMissing, targetMissing, mismatch);
                    }
                }
            } catch (Exception e) {
                log.warn("Error collecting statistics for segment: {}", segmentId, e);
            }
        }, executorProvider.getIoExecutor());
    }

    /**
     * Process diff results.
     */
    private int processDiffResults(List<Object[]> differences, JoinQueryPlan queryPlan, String segmentId,
            InfoTreeRecorder infoTreeRecorder) {
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
        return buildJoinQueryPlan(table1, table2, segmentAlias1, segmentAlias2,
                table1.buildWhereClause(), table2.buildWhereClause());
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
        double differencePercentage = (sourceRowCount + targetRowCount > 0)
                ? (double) totalDifferences / Math.max(sourceRowCount, targetRowCount) * 100.0
                : 0.0;

        DiffResult.DiffStatistics diffStatistics = DiffResult.DiffStatistics.builder()
                .sourceRowCount(sourceRowCount)
                .targetRowCount(targetRowCount)
                .sourceMissingCount(sourceMissing)
                .targetMissingCount(targetMissing)
                .mismatchCount(mismatched)
                .totalDifferences(totalDifferences)
                .processingTimeMs((Long) performanceMonitor.getMetrics().getOrDefault("root_duration", 0L))
                .unchangedCount(Math.max(0, Math.min(sourceRowCount, targetRowCount) - mismatched))
                .differencePercentage(differencePercentage)
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
        String raw = String.join("|",
                String.valueOf(minKey),
                String.valueOf(maxKey),
                String.valueOf(where),
                String.valueOf(limitOffset),
                String.valueOf(index));
        return "seg" + Integer.toHexString(Objects.hash(raw));
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

        public static JoinDifferOptions defaultOptions() {
            return new JoinDifferOptions(true);
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
            metrics.put(segmentId + "_source_count", statRow[0]);
            metrics.put(segmentId + "_target_count", statRow[1]);
            metrics.put(segmentId + "_source_missing", statRow[2]);
            metrics.put(segmentId + "_target_missing", statRow[3]);
            metrics.put(segmentId + "_mismatch", statRow[4]);
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
