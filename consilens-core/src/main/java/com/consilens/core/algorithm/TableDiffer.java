package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.diff.DiffEmitter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffSink;
import com.consilens.core.diff.InfoTreeRecorder;
import com.consilens.core.diff.InMemoryDiffSink;
import com.consilens.core.diff.SchemaCompatibility;
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
        ((InMemoryDiffSink) this.diffSink).setMaxDifferences(config.getMaxDifferences());
    }

    @Override
    public void setDiffSink(DiffSink diffSink) {
        this.diffSink = diffSink != null ? diffSink : DiffSink.noop();
        if (this.diffSink instanceof InMemoryDiffSink) {
            // 换 sink 之后上限也要跟着走，否则外部注入的 sink 等于绕过了 maxDifferences
            ((InMemoryDiffSink) this.diffSink).setMaxDifferences(config.getMaxDifferences());
        }
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

    /**
     * 尽早失败用的预检查：真正"不会超限"的保证在 {@link InMemoryDiffSink#onDiffRow} 里
     * （按当前条数原子判断）。这里只负责在批次很大时给出更早、更省事的错误。
     */
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
            SchemaCompatibility schemaCompatibility = validateAndAdjustColumns(table1, table2);

            // 严格档（validateUniqueKeys=true，非默认）：键必须唯一。重复键下 join 会 N×M 倍增、
            // checksum 的归因会退化成整键 SM+TM，都属于"按键比对不成立"，必须在开始前明确失败并给出证据。
            if (config.isValidateUniqueKeys()) {
                DuplicateKeyGuard.assertUnique(table1, table2);
            }

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
                                .schemaCompatibility(schemaCompatibility)
                                .build();
                    });

        } catch (Exception e) {
            log.error("Error during table diffing", e);
            // 唯一键校验失败、预算超限等"业务性拒绝"要保留原始信息（键与两侧行数），
            // 不能被 "Table diff failed" 包一层导致明细丢失。
            if (e instanceof com.consilens.connector.api.ConnectorException
                    || e instanceof com.consilens.core.exception.BudgetExceededException) {
                return CompletableFuture.failedFuture(e);
            }
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
    protected SchemaCompatibility validateAndAdjustColumns(TableSegment table1, TableSegment table2) {
        // Validate key columns count match (names can be different, but count must match)
        if (table1.getKeyColumns().size() != table2.getKeyColumns().size()) {
            log.warn("Table1 key columns: {}, Table2 key columns: {}",
                     table1.getKeyColumns(), table2.getKeyColumns());
            throw new IllegalArgumentException(String.format(
                "Key columns count must match between tables. Table1 has %d, Table2 has %d",
                table1.getKeyColumns().size(), table2.getKeyColumns().size()));
        }

        /*
         * Extra (non-key) columns are compared positionally (LocalDiffEngine, row checksum), so the
         * two sides must project the same number of columns. Allowing a mismatch silently maps value
         * i of one side onto a different column of the other side, producing bogus MISMATCH rows or
         * hiding real ones - fail fast instead.
         */
        if (table1.getExtraColumns().size() != table2.getExtraColumns().size()) {
            log.warn("Table1 extra columns: {}, Table2 extra columns: {}",
                     table1.getExtraColumns(), table2.getExtraColumns());
            throw new IllegalArgumentException(String.format(
                "Extra (non-key) column count must match between tables. Table1 has %d %s, Table2 has %d %s",
                table1.getExtraColumns().size(), table1.getExtraColumns(),
                table2.getExtraColumns().size(), table2.getExtraColumns()));
        }

        // NULL 键无法参与有序分段：边界探测/键区间谓词/DiffResult 去重都假设键非空，
        // 一旦键列可空，NULL 行不会被任何 [min,max] 段命中（SQL 三值逻辑），会被静默漏报。
        // 默认在比对开始前直接拒绝；executionOptions.allowNullableKeys=true 时保留可空键，
        // 由 TableSegment 的 IS NULL 边界谓词与 ValueNormalizer 的 NULL 归一化处理。
        validateKeyNullability(table1);
        validateKeyNullability(table2);

        List<String> warnings = new ArrayList<>();
        warnings.addAll(precisionWarnings(table1));
        warnings.addAll(precisionWarnings(table2));
        warnJsonCrossDialect(table1, table2).ifPresent(warnings::add);
        warnings.addAll(warnJsonKeyColumns(table1));
        warnings.addAll(warnJsonKeyColumns(table2));
        TemporalShapeCompatibility.describeIfRisky(table1, table2)
                .ifPresent(message -> {
                    log.warn(message);
                    warnings.add(message);
                });
        KeyCollationCompatibility.describeIfRisky(table1)
                .ifPresent(message -> {
                    log.warn(message);
                    warnings.add(message);
                });
        KeyCollationCompatibility.describeIfRisky(table2)
                .ifPresent(message -> {
                    log.warn(message);
                    warnings.add(message);
                });

        log.info("Column validation completed for tables {} and {} (key columns: {}, extra columns: {} vs {})",
                table1.getTablePath(), table2.getTablePath(),
                table1.getKeyColumns().size(), table1.getExtraColumns().size(), table2.getExtraColumns().size());
        return buildSchemaCompatibility(table1, table2, warnings);
    }

    /**
     * Builds an additive structured report from the same checks that already run in
     * {@link #validateAndAdjustColumns(TableSegment, TableSegment)}. It never changes
     * comparison behavior; incompatible columns are reported, not skipped silently.
     */
    private SchemaCompatibility buildSchemaCompatibility(TableSegment table1,
                                                         TableSegment table2,
                                                         List<String> warnings) {
        List<String> leftColumns = new ArrayList<>(table1.getKeyColumns());
        leftColumns.addAll(table1.getExtraColumns());
        List<String> rightColumns = new ArrayList<>(table2.getKeyColumns());
        rightColumns.addAll(table2.getExtraColumns());

        List<SchemaCompatibility.ColumnCompatibility> columns = new ArrayList<>();
        boolean incompatible = false;
        boolean normalized = false;
        int size = Math.max(leftColumns.size(), rightColumns.size());
        for (int index = 0; index < size; index++) {
            String leftName = index < leftColumns.size() ? leftColumns.get(index) : null;
            String rightName = index < rightColumns.size() ? rightColumns.get(index) : null;
            if (leftName == null && rightName == null) {
                continue;
            }
            if (leftName == null) {
                normalized = true;
                columns.add(SchemaCompatibility.ColumnCompatibility.builder()
                        .targetColumn(rightName)
                        .targetType(typeName(table2, rightName))
                        .action(SchemaCompatibility.Action.MISSING_SOURCE)
                        .reason("column exists only on the target side")
                        .build());
                continue;
            }
            if (rightName == null) {
                normalized = true;
                columns.add(SchemaCompatibility.ColumnCompatibility.builder()
                        .sourceColumn(leftName)
                        .sourceType(typeName(table1, leftName))
                        .action(SchemaCompatibility.Action.MISSING_TARGET)
                        .reason("column exists only on the source side")
                        .build());
                continue;
            }

            DataType sourceType = dataType(table1, leftName);
            DataType targetType = dataType(table2, rightName);
            SchemaCompatibility.Action action = compareTypes(sourceType, targetType);
            if (action == SchemaCompatibility.Action.INCOMPATIBLE) {
                incompatible = true;
            } else if (action != SchemaCompatibility.Action.DIRECT) {
                normalized = true;
            }
            columns.add(SchemaCompatibility.ColumnCompatibility.builder()
                    .sourceColumn(leftName)
                    .targetColumn(rightName)
                    .sourceType(sourceType.name())
                    .targetType(targetType.name())
                    .action(action)
                    .reason(reason(action, sourceType, targetType))
                    .build());
        }

        SchemaCompatibility.Status status = incompatible
                ? SchemaCompatibility.Status.INCOMPATIBLE
                : normalized
                ? SchemaCompatibility.Status.COMPATIBLE_WITH_NORMALIZATION
                : SchemaCompatibility.Status.COMPATIBLE;
        return SchemaCompatibility.builder()
                .status(status)
                .columns(columns)
                .warnings(warnings)
                .build();
    }

    private SchemaCompatibility.Action compareTypes(DataType sourceType, DataType targetType) {
        if (sourceType == DataType.UNKNOWN || targetType == DataType.UNKNOWN) {
            return SchemaCompatibility.Action.NORMALIZE;
        }
        if (sourceType == targetType) {
            return SchemaCompatibility.Action.DIRECT;
        }
        if (sourceType.isCompatibleWith(targetType)) {
            return SchemaCompatibility.Action.NORMALIZE;
        }
        if ((sourceType == DataType.JSON || sourceType == DataType.JSONB)
                && (targetType == DataType.JSON || targetType == DataType.JSONB)) {
            return SchemaCompatibility.Action.NORMALIZE;
        }
        if ((sourceType == DataType.BOOLEAN || sourceType == DataType.BIT)
                && (targetType == DataType.BOOLEAN || targetType == DataType.BIT)) {
            return SchemaCompatibility.Action.NORMALIZE;
        }
        return SchemaCompatibility.Action.INCOMPATIBLE;
    }

    private String reason(SchemaCompatibility.Action action, DataType sourceType, DataType targetType) {
        switch (action) {
            case DIRECT:
                return "same type";
            case NORMALIZE:
                return "compatible type families; normalization required (" + sourceType + " -> " + targetType + ")";
            case INCOMPATIBLE:
                return "incompatible type families (" + sourceType + " vs " + targetType + ")";
            default:
                return action.name();
        }
    }

    private DataType dataType(TableSegment table, String column) {
        if (table == null || column == null || table.getSchema() == null || table.getSchema().isEmpty()) {
            return DataType.UNKNOWN;
        }
        return table.getSchema().get().getColumn(column)
                .map(ColumnInfo::getType)
                .orElse(DataType.UNKNOWN);
    }

    private String typeName(TableSegment table, String column) {
        return dataType(table, column).name();
    }

    /**
     * 归一化把 DECIMAL/NUMERIC 固定到 4 位小数、浮点固定到 6 位（SQL 与 Java 两侧同口径），
     * 列 scale 超过该精度时，末位差异会被抹平。这里显式告警，避免"看起来一致"却掩盖差异；
     * 不直接失败，因为是否可接受取决于业务对末位精度的要求。
     */
    private List<String> precisionWarnings(TableSegment table) {
        int precision = config != null
                ? config.getDecimalScale() : ValueNormalizer.DEFAULT_DECIMAL_SCALE;
        List<String> risky = decimalColumnsBeyondScale(table, precision);
        if (risky.isEmpty()) {
            return List.of();
        }
        String message = String.format(
                "Table %s has decimal column(s) with scale > %d: %s. Comparison normalizes decimals to %d "
                        + "decimal places, so differences beyond that are treated as equal. "
                        + "Raise the normalization precision (strategy.normalization) or compare these columns "
                        + "separately if that matters.",
                table.getTablePath(), precision, risky, precision);
        log.warn(message);
        return List.of(message);
    }

    /** 供测试与告警复用：返回 scale 超过给定归一化精度的 DECIMAL/NUMERIC 列。 */
    static List<String> decimalColumnsBeyondScale(TableSegment table, int precision) {
        if (table == null || table.getSchema() == null || table.getSchema().isEmpty()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>(table.getKeyColumns());
        columns.addAll(table.getExtraColumns());
        List<String> risky = new ArrayList<>();
        for (String column : columns) {
            Optional<ColumnInfo> info = table.getSchema().get().getColumn(column);
            if (info.isEmpty()) {
                continue;
            }
            ColumnInfo columnInfo = info.get();
            boolean decimal = columnInfo.getType() == com.consilens.connector.api.model.DataType.DECIMAL
                    || columnInfo.getType() == com.consilens.connector.api.model.DataType.NUMERIC;
            int scale = columnInfo.getScale().orElse(0);
            if (decimal && scale > precision) {
                risky.add(column + "(scale=" + scale + ")");
            }
        }
        return risky;
    }

    /**
     * JSON 归一化是各 handle 的"文本级 canonical 化"，跨方言渲染（键序/空格/转义）并不完全一致。
     * 同方言内可闭环；跨方言时显式告警，避免把"渲染差异"当成业务差异却无人知晓。
     */
    private Optional<String> warnJsonCrossDialect(TableSegment table1, TableSegment table2) {
        if (table1.getSchema() == null || table1.getSchema().isEmpty()
                || table2.getSchema() == null || table2.getSchema().isEmpty()
                || table1.getDatabase() == null || table2.getDatabase() == null) {
            return Optional.empty();
        }
        String type1 = table1.getDatabase().getConnectorType();
        String type2 = table2.getDatabase().getConnectorType();
        if (type1 == null || type2 == null || type1.equalsIgnoreCase(type2)) {
            return Optional.empty();
        }
        List<String> jsonColumns = new ArrayList<>();
        for (String column : table1.getExtraColumns()) {
            Optional<ColumnInfo> info = table1.getSchema().get().getColumn(column);
            if (info.isEmpty()) {
                continue;
            }
            com.consilens.connector.api.model.DataType type = info.get().getType();
            if (type == com.consilens.connector.api.model.DataType.JSON
                    || type == com.consilens.connector.api.model.DataType.JSONB) {
                jsonColumns.add(column);
            }
        }
        if (jsonColumns.isEmpty()) {
            return Optional.empty();
        }
        String message = String.format(
                "Comparing JSON column(s) %s across %s and %s: JSON normalization is textual and "
                        + "rendering differs between engines (key order/whitespace/escaping). A mismatch may be "
                        + "formatting rather than data; verify with a normalized cast or exclude the column.",
                jsonColumns, type1, type2);
        log.warn(message);
        return Optional.of(message);
    }

    /**
     * JSON/JSONB 作为主键时，各引擎的文本渲染（键序、空格、数字形态）不保证一致，
     * 而键区间的生成、校验和分组、DiffResult 去重都依赖"同一把键渲染相同"。
     * 这类形态无法用归一化兜住，显式告警。
     */
    private List<String> warnJsonKeyColumns(TableSegment table) {
        List<String> jsonKeys = jsonKeyColumns(table);
        if (jsonKeys.isEmpty()) {
            return List.of();
        }
        String message = String.format(
                "Table %s uses JSON/JSONB column(s) as key: %s. Key rendering is not stable across "
                        + "engines (key order, whitespace, number formats), so segmentation and checksum "
                        + "grouping can miss rows. Use a scalar key or a deterministic normalized cast.",
                table.getTablePath(), jsonKeys);
        log.warn(message);
        return List.of(message);
    }

    /** 供告警与测试复用：返回参与主键的 JSON/JSONB 列。 */
    static List<String> jsonKeyColumns(TableSegment table) {
        if (table == null || table.getSchema() == null || table.getSchema().isEmpty()) {
            return List.of();
        }
        List<String> risky = new ArrayList<>();
        for (String key : table.getKeyColumns()) {
            Optional<ColumnInfo> column = table.getSchema().get().getColumn(key);
            if (column.isEmpty()) {
                continue;
            }
            if (column.get().getType() == com.consilens.connector.api.model.DataType.JSON
                    || column.get().getType() == com.consilens.connector.api.model.DataType.JSONB) {
                risky.add(key);
            }
        }
        return risky;
    }

    private void validateKeyNullability(TableSegment table) {
        if (table.getSchema() == null || table.getSchema().isEmpty()) {
            return;
        }
        // 过滤条件里显式排除了 NULL 键时（错误信息推荐的修复方式），
        // 可空主键不再有漏报风险，不能继续拒绝。
        String where = table.getWhereClause().orElse("")
                .toLowerCase(java.util.Locale.ROOT)
                .replace("`", "")
                .replace("\"", "");
        List<String> nullableKeys = new ArrayList<>();
        for (String key : table.getKeyColumns()) {
            Optional<ColumnInfo> column = table.getSchema().get().getColumn(key);
            if (column.isPresent() && column.get().isNullable() && !excludesNullKeys(where, key)) {
                nullableKeys.add(key);
            }
        }
        if (nullableKeys.isEmpty()) {
            return;
        }
        if (config != null && config.isAllowNullableKeys()) {
            log.warn("Table {} has nullable key column(s) {} and allowNullableKeys=true. "
                            + "NULL keys will be compared using the engine's IS NULL/归一化 semantics; "
                            + "make sure the data source and both sides agree on NULL ordering.",
                    table.getTablePath(), nullableKeys);
            return;
        }
        String key = nullableKeys.get(0);
        throw new IllegalArgumentException(String.format(
            "Key column '%s' of table %s is nullable. NULL keys cannot be ordered or segmented, "
                + "so their rows would be silently skipped. Use a NOT NULL key column, add a "
                + "filter that excludes NULL keys (e.g. %s IS NOT NULL), or set "
                + "executionOptions.allowNullableKeys=true to compare NULL keys explicitly.",
            key, table.getTablePath(), key));
    }

    /** where 条件（已小写、去引号）里是否显式排除了该键的 NULL 值。 */
    /**
     * 该表是否可能含 NULL 键（键列可空且过滤条件没有排除 NULL）。
     * <p>MIN()/MAX() 与 {@code k >= ?} 区间谓词都丢弃 NULL 行，所以这种表的行数可能分布在
     * 任何分段之外；分段前必须先问这个问题，否则 NULL 键行会被静默漏报。
     */
    static boolean mayContainNullKeys(TableSegment table) {
        if (table == null || table.getSchema() == null || !table.getSchema().isPresent()) {
            return false;
        }
        String where = table.getWhereClause().orElse("")
                .toLowerCase(java.util.Locale.ROOT)
                .replace("`", "")
                .replace("\"", "");
        for (String key : table.getKeyColumns()) {
            Optional<ColumnInfo> column = table.getSchema().get().getColumn(key);
            if (column.isPresent() && column.get().isNullable() && !excludesNullKeys(where, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean excludesNullKeys(String normalizedWhere, String key) {
        if (normalizedWhere.isEmpty()) {
            return false;
        }
        // 只有合取条件才能保证所有行都排除 NULL；OR 的另一支仍可能带 NULL 键，
        // 而区间谓词对 NULL 恒为 UNKNOWN，这些行会被静默跳过。
        if (normalizedWhere.matches("(?s).*\\bor\\b.*")) {
            return false;
        }
        String column = key.toLowerCase(java.util.Locale.ROOT);
        return normalizedWhere.matches("(?s).*\\b" + java.util.regex.Pattern.quote(column)
                + "\\s+is\\s+not\\s+null\\b.*");
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
     * Length-prefix key parts so segment identifiers stay unambiguous and collision-free.
     */
    protected static String encodeKeyParts(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = part != null ? part : "null";
            key.append(value.length()).append(':').append(value).append('|');
        }
        return key.toString();
    }

    /**
     * Configuration for diff algorithms.
     */
    @Getter
    public static class DifferConfig {
        /** 容差内样本默认上限：与执行层默认值保持一致（报告要看得见，又不能无界占内存）。 */
        static final int DEFAULT_WITHIN_TOLERANCE_SAMPLES = 100;
        /** 与执行层一致的硬顶，避免 SDK 调用方传一个天文数字把样本灌满内存。 */
        static final int MAX_WITHIN_TOLERANCE_SAMPLES = 10_000;

        private final int bisectionFactor;
        private final long bisectionThreshold;
        private final int maxDepth;
        private final ChecksumAlgorithm checksumAlgorithm;
        private final LocalCompareMode localCompareMode;
        private final ConcurrencyConfig concurrencyConfig;
        private final long maxDifferences;
        private final boolean validateUniqueKeys;
        private final int decimalScale;
        private final boolean allowNullableKeys;
        private final long maxDurationMs;
        private final long maxRowsScanned;
        private final long maxBytesFetched;
        /** 原始归一化 spec：容差等"取数点之后"的规则由终局比较解析。 */
        private final com.consilens.connector.api.normalization.NormalizationSpec normalizationSpec;
        /** 容差内行是否计入差异：IGNORE（默认，不计入、报告单列）或 REPORT（仍计入）。 */
        private final com.consilens.core.compare.WithinToleranceMode withinToleranceMode;
        /** 容差内行随结果保留的样本上限；0 表示只计数不保留行。 */
        private final int withinToleranceSamples;

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys,
                           int decimalScale, boolean allowNullableKeys) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm, localCompareMode,
                    concurrencyConfig, maxDifferences, validateUniqueKeys, decimalScale,
                    allowNullableKeys, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, null);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys,
                           int decimalScale, boolean allowNullableKeys,
                           long maxDurationMs, long maxRowsScanned, long maxBytesFetched) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm, localCompareMode,
                    concurrencyConfig, maxDifferences, validateUniqueKeys, decimalScale,
                    allowNullableKeys, maxDurationMs, maxRowsScanned, maxBytesFetched, null);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys,
                           int decimalScale, boolean allowNullableKeys,
                           long maxDurationMs, long maxRowsScanned, long maxBytesFetched,
                           com.consilens.connector.api.normalization.NormalizationSpec normalizationSpec) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm, localCompareMode, concurrencyConfig,
                    maxDifferences, validateUniqueKeys, decimalScale, allowNullableKeys, maxDurationMs,
                    maxRowsScanned, maxBytesFetched, normalizationSpec,
                    com.consilens.core.compare.WithinToleranceMode.IGNORE,
                    DEFAULT_WITHIN_TOLERANCE_SAMPLES);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys,
                           int decimalScale, boolean allowNullableKeys,
                           long maxDurationMs, long maxRowsScanned, long maxBytesFetched,
                           com.consilens.connector.api.normalization.NormalizationSpec normalizationSpec,
                           com.consilens.core.compare.WithinToleranceMode withinToleranceMode,
                           int withinToleranceSamples) {
            this.bisectionFactor = bisectionFactor;
            this.bisectionThreshold = bisectionThreshold;
            this.maxDepth = 10;
            this.checksumAlgorithm = checksumAlgorithm != null ? checksumAlgorithm : ChecksumAlgorithm.CONCAT;
            this.localCompareMode = localCompareMode != null ? localCompareMode : LocalCompareMode.FULL;
            this.concurrencyConfig = concurrencyConfig != null ? concurrencyConfig : ConcurrencyConfig.defaultConfig();
            this.maxDifferences = maxDifferences > 0 ? maxDifferences : 1_000_000L;
            this.validateUniqueKeys = validateUniqueKeys;
            this.decimalScale = decimalScale > 0
                    ? decimalScale : com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE;
            this.allowNullableKeys = allowNullableKeys;
            this.maxDurationMs = maxDurationMs > 0 ? maxDurationMs : Long.MAX_VALUE;
            this.maxRowsScanned = maxRowsScanned > 0 ? maxRowsScanned : Long.MAX_VALUE;
            this.maxBytesFetched = maxBytesFetched > 0 ? maxBytesFetched : Long.MAX_VALUE;
            this.normalizationSpec = normalizationSpec;
            this.withinToleranceMode = withinToleranceMode != null
                    ? withinToleranceMode : com.consilens.core.compare.WithinToleranceMode.IGNORE;
            this.withinToleranceSamples = withinToleranceSamples >= 0
                    ? Math.min(withinToleranceSamples, MAX_WITHIN_TOLERANCE_SAMPLES)
                    : DEFAULT_WITHIN_TOLERANCE_SAMPLES;
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys, int decimalScale) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm, localCompareMode,
                    concurrencyConfig, maxDifferences, validateUniqueKeys, decimalScale, false);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences, boolean validateUniqueKeys) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm, localCompareMode,
                    concurrencyConfig, maxDifferences, validateUniqueKeys,
                    com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE, false);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig,
                           long maxDifferences) {
            this(bisectionFactor, bisectionThreshold, checksumAlgorithm,
                 localCompareMode, concurrencyConfig, maxDifferences, false);
        }

        /** 严格档开关：true 时比对前做唯一键探针，命中重复键直接失败并列出键与行数。 */
        public boolean isValidateUniqueKeys() {
            return validateUniqueKeys;
        }

        /** 可空键开关：true 时跳过“键列可空即拒绝”的前置校验。 */
        public boolean isAllowNullableKeys() {
            return allowNullableKeys;
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           LocalCompareMode localCompareMode,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, localCompareMode, concurrencyConfig, 1_000_000L);
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

        public DifferConfig(int bisectionFactor, long bisectionThreshold,
                           ChecksumAlgorithm checksumAlgorithm,
                           ConcurrencyConfig concurrencyConfig) {
            this(bisectionFactor, bisectionThreshold,
                 checksumAlgorithm, LocalCompareMode.FULL, concurrencyConfig);
        }

        public static DifferConfig defaultConfig() {
            return new DifferConfig(32, 16384, ChecksumAlgorithm.CONCAT,
                    LocalCompareMode.FULL, ConcurrencyConfig.defaultConfig());
        }

    }
}
