package com.consilens.core.algorithm;

import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.model.DataType;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs efficient in-memory comparison of row sets.
 */
@Slf4j
public class LocalDiffEngine {

    /**
     * Find differences between two sets of rows (backward compatibility).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2) {
        // Call the new method without column types (backward compatibility)
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Find differences between two sets of rows (type-aware).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @param columnTypes1  Column data types for table 1
     * @param columnTypes2  Column data types for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2) {
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                columnTypes1, columnTypes2, null, null);
    }

    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2) {
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2, (Map<String, ColumnTolerance>) null);
    }

    /**
     * Find differences with dialect-aware value normalization and optional
     * per-column numeric tolerance resolved from the normalization spec.
     *
     * @param spec normalization spec; tolerance rules (format_number with
     *             {@code tolerance}/{@code relativeTolerance}) apply during
     *             value comparison. Null means no tolerance.
     */
    public static List<DiffRow> findDifferencesWithTolerance(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2,
            com.consilens.connector.api.normalization.NormalizationSpec spec) {
        return findDifferencesWithToleranceOutcome(rows1, rows2, keyColumns1, extraColumns1, keyColumns2,
                extraColumns2, columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2, spec, false)
                .getDifferences();
    }

    /**
     * 与 {@link #findDifferencesWithTolerance} 同一套判定，额外保留"容差内"的行。
     *
     * <p>为什么需要单独一路：容差判等的行若直接丢弃，报告里就看不到"这里差了 0.004 但被容差吸收"，
     * 一旦容差配宽了（比如 1.0），只能看到"完全一致"，没有任何审计线索。调用方按
     * {@code withinTolerance} 口径决定这些行是计数还是只列出。
     *
     * @param captureWithinTolerance false 时不采集样本，行为与旧方法完全一致（省去一次行构造）
     */
    public static ToleranceOutcome findDifferencesWithToleranceOutcome(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2,
            com.consilens.connector.api.normalization.NormalizationSpec spec,
            boolean captureWithinTolerance) {

        // 容差只作用于非键列：键必须精确相等，否则键分组/去重语义会被破坏
        List<String> comparedColumns1 = new ArrayList<>(keyColumns1);
        comparedColumns1.addAll(extraColumns1);
        List<String> comparedColumns2 = new ArrayList<>(keyColumns2);
        comparedColumns2.addAll(extraColumns2);
        Map<String, ColumnTolerance> tolerances = ColumnToleranceResolver.resolve(
                spec,
                withoutKeys(comparedColumns1, keyColumns1),
                withoutKeys(comparedColumns2, keyColumns2),
                columnTypes1, columnTypes2);

        return findDifferencesInternal(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2, tolerances,
                captureWithinTolerance);
    }

    /** 容差判等的行与真正的差异分开返回。 */
    public static final class ToleranceOutcome {
        private final List<DiffRow> differences;
        private final List<DiffRow> withinTolerance;

        ToleranceOutcome(List<DiffRow> differences, List<DiffRow> withinTolerance) {
            this.differences = differences;
            this.withinTolerance = withinTolerance;
        }

        public List<DiffRow> getDifferences() {
            return differences;
        }

        public List<DiffRow> getWithinTolerance() {
            return withinTolerance;
        }
    }

    private static List<String> withoutKeys(List<String> columns, List<String> keys) {
        if (columns == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(columns);
        if (keys != null) {
            result.removeAll(keys);
        }
        return result;
    }

    /**
     * Find differences with dialect-aware value normalization. Each side's
     * {@link DataTypeHandler} maps driver-specific representations onto the
     * common comparison form (e.g. MySQL YEAR as {@code java.sql.Date}).
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2,
            Map<String, ColumnTolerance> tolerances) {
        return findDifferencesInternal(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2, tolerances, false)
                .getDifferences();
    }

    private static ToleranceOutcome findDifferencesInternal(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2,
            Map<String, ColumnTolerance> tolerances,
            boolean captureWithinTolerance) {

        log.info("Finding differences between {} and {} rows", rows1.size(), rows2.size());

        // Group rows by primary keys so we can reason about ADDED/REMOVED/MODIFIED row-by-row.
        // Keys are grouped by their *normalized* form: both sides may declare the same logical key
        // with different physical types (INT vs VARCHAR), and comparing raw values would make
        // Integer(21) and String("21") distinct keys, so every row would be reported as
        // one-sided missing.
        Map<String, PrimaryKeyGroup> rowsByPk1 = groupRowsByPrimaryKey(rows1, keyColumns1, columnTypes1, dataTypeHandler1);
        Map<String, PrimaryKeyGroup> rowsByPk2 = groupRowsByPrimaryKey(rows2, keyColumns2, columnTypes2, dataTypeHandler2);

        List<DiffRow> differences = new ArrayList<>();
        List<DiffRow> withinTolerance = captureWithinTolerance ? new ArrayList<>() : null;

        // Process keys from table1, then keys that only exist in table2, so we
        // do not need a third full-size collection for all primary keys.
        for (Map.Entry<String, PrimaryKeyGroup> entry : rowsByPk1.entrySet()) {
            PrimaryKeyGroup group1 = entry.getValue();
            PrimaryKeyGroup group2 = rowsByPk2.get(entry.getKey());
            List<Object> pk = group1.rawKey;
            List<Object[]> pkRows1 = group1.rows;
            List<Object[]> pkRows2 = group2 == null ? Collections.emptyList() : group2.rows;

            // Extract relevant columns (excluding ignored columns)
            List<Object[]> relevantRows1 = extractRelevantColumns(pkRows1, keyColumns1, extraColumns1);
            List<Object[]> relevantRows2 = extractRelevantColumns(pkRows2, keyColumns2, extraColumns2);

            // Determine differences for this primary key
            List<String> columnNames1 = new ArrayList<>(keyColumns1);
            columnNames1.addAll(extraColumns1);
            List<String> columnNames2 = new ArrayList<>(keyColumns2);
            columnNames2.addAll(extraColumns2);
            List<DiffRow> pkDifferences = findPrimaryKeyDifferences(pk, pkRows1, pkRows2, relevantRows1, relevantRows2,
                    columnNames1, columnNames2, columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2,
                    tolerances, withinTolerance);
            differences.addAll(pkDifferences);
        }

        for (Map.Entry<String, PrimaryKeyGroup> entry : rowsByPk2.entrySet()) {
            if (rowsByPk1.containsKey(entry.getKey())) {
                continue;
            }
            PrimaryKeyGroup group2 = entry.getValue();
            List<Object[]> pkRows2 = group2.rows;
            List<Object[]> relevantRows2 = extractRelevantColumns(pkRows2, keyColumns2, extraColumns2);
            List<String> columnNames1 = new ArrayList<>(keyColumns1);
            columnNames1.addAll(extraColumns1);
            List<String> columnNames2 = new ArrayList<>(keyColumns2);
            columnNames2.addAll(extraColumns2);
            List<DiffRow> pkDifferences = findPrimaryKeyDifferences(group2.rawKey, Collections.emptyList(), pkRows2,
                    Collections.emptyList(), relevantRows2, columnNames1, columnNames2, columnTypes1, columnTypes2,
                    dataTypeHandler1, dataTypeHandler2, tolerances, withinTolerance);
            differences.addAll(pkDifferences);
        }

        log.info("Found {} total differences ({} within tolerance)", differences.size(),
                withinTolerance == null ? 0 : withinTolerance.size());
        return new ToleranceOutcome(differences,
                withinTolerance == null ? Collections.emptyList() : withinTolerance);
    }

    /**
     * Rows sharing one logical primary key, kept together with the raw key of their own side so
     * difference rows can still report the side-native key values.
     */
    private static final class PrimaryKeyGroup {
        private final List<Object> rawKey;
        private final List<Object[]> rows = new ArrayList<>();

        private PrimaryKeyGroup(List<Object> rawKey) {
            this.rawKey = rawKey;
        }
    }

    /**
     * Group rows by their primary key values, normalized the same way the checksum path normalizes
     * keys (see {@link ValueNormalizer}), then joined on the normalized form.
     */
    private static Map<String, PrimaryKeyGroup> groupRowsByPrimaryKey(List<Object[]> rows,
            List<String> keyColumns, Map<String, DataType> columnTypes, DataTypeHandler dataTypeHandler) {
        Map<String, PrimaryKeyGroup> groupedRows = new LinkedHashMap<>();
        int keyColumnCount = keyColumns.size();

        for (Object[] row : rows) {
            String normalizedKey = normalizedKey(row, keyColumns, columnTypes, dataTypeHandler);
            PrimaryKeyGroup group = groupedRows.get(normalizedKey);
            if (group == null) {
                // Copy only the PK portion so duplicate rows with the same key compress into the same bucket.
                group = new PrimaryKeyGroup(Arrays.asList(Arrays.copyOf(row, keyColumnCount)));
                groupedRows.put(normalizedKey, group);
            }
            group.rows.add(row);
        }

        return groupedRows;
    }

    private static String normalizedKey(Object[] row, List<String> keyColumns,
            Map<String, DataType> columnTypes, DataTypeHandler dataTypeHandler) {
        StringBuilder builder = new StringBuilder();
        int keyColumnCount = keyColumns.size();
        for (int index = 0; index < keyColumnCount; index++) {
            String column = keyColumns.get(index);
            DataType dataType = columnTypes == null ? null : columnTypes.get(column);
            Object value = normalizeForComparison(row[index], dataType, dataTypeHandler);
            // 长度前缀避免键值本身含分隔符时不同复合键碰撞被并成同一组
            String text = normalizeKeyValue(value, dataType);
            builder.append(text.length()).append(':').append(text);
        }
        return builder.toString();
    }

    /**
     * 主键分量归一化。DECIMAL/NUMERIC 键不能用 ValueNormalizer 的 4 位小数形态：
     * 1.00001 与 1.00002 都会被渲染成 "1.0000"，两个不同的主键会被并进同一个分组，
     * 从而把"两侧各有一条不同键"误判成"同一键且值相同"（整体差异被吃掉）。
     * {@link ValueNormalizer#normalizeKey} 对定点键用精确十进制（去尾零）。
     */
    private static String normalizeKeyValue(Object value, DataType dataType) {
        return ValueNormalizer.normalizeKey(value, dataType == null ? DataType.UNKNOWN : dataType);
    }

    /**
     * Extract relevant columns from rows, excluding ignored columns.
     */
    private static List<Object[]> extractRelevantColumns(List<Object[]> rows, List<String> keyColumns,
            List<String> extraColumns) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        // For this implementation, assume all columns are relevant
        // In a more complex version, this would handle ignored columns
        return rows.stream()
                .map(row -> Arrays.copyOf(row, row.length))
                .collect(Collectors.toList());
    }

    /**
     * Find differences for rows with the same primary key.
     */
    private static List<DiffRow> findPrimaryKeyDifferences(
            List<Object> primaryKey,
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<Object[]> relevantRows1,
            List<Object[]> relevantRows2,
            List<String> columnNames1,
            List<String> columnNames2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2,
            Map<String, ColumnTolerance> tolerances,
            List<DiffRow> withinTolerance) {

        List<DiffRow> differences = new ArrayList<>();

        // Cases to handle:
        // 1. Rows exist only in table 1 -> REMOVED
        // 2. Rows exist only in table 2 -> ADDED
        // 3. Rows exist in both but have different values -> MODIFIED
        // 4. Multiple rows with same PK in either table -> handle duplicates

        if (relevantRows1.isEmpty() && !relevantRows2.isEmpty()) {
            // Rows only in table 2, therefore ADDED. Iterate the *relevant* (projected) rows:
            // rows2 still contains ignored columns, so its values would not line up with
            // columnNames2. Each row needs an occurrence, otherwise storeDifferences()
            // deduplicates all rows of the key into a single diff row.
            for (int i = 0; i < relevantRows2.size(); i++) {
                DiffRow diff = DiffRow.added(primaryKey, Arrays.asList(relevantRows2.get(i)), columnNames2);
                differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                        i + 1, 0, relevantRows2.size())));
            }
        } else if (!relevantRows1.isEmpty() && relevantRows2.isEmpty()) {
            // Rows only in table 1, therefore REMOVED
            for (int i = 0; i < relevantRows1.size(); i++) {
                DiffRow diff = DiffRow.removed(primaryKey, Arrays.asList(relevantRows1.get(i)), columnNames1);
                differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                        i + 1, relevantRows1.size(), 0)));
            }
        } else if (!relevantRows1.isEmpty()) {
            // Rows exist in both tables
            if (relevantRows1.size() == 1 && relevantRows2.size() == 1) {
                // Single row in each - compare values once,同时收集"精确不等但容差判等"的列：
                // 原来先 rowsEqualNormalized 再 findChangedColumnsWithDetails 会把同一行列比较跑两遍，
                // 且拿不到"被容差吸收"的证据。
                Object[] row1 = relevantRows1.get(0);
                Object[] row2 = relevantRows2.get(0);
                List<String> changedColumns1 = new ArrayList<>();
                List<String> changedColumns2 = new ArrayList<>();
                List<String> toleranceColumns1 = new ArrayList<>();
                List<String> toleranceColumns2 = new ArrayList<>();
                Map<String, Double> toleranceDeltas = new LinkedHashMap<>();
                List<Integer> changedIndices = findChangedColumnsWithDetails(row1, row2, columnNames1, columnNames2,
                        changedColumns1, changedColumns2, toleranceColumns1, toleranceColumns2, toleranceDeltas,
                        columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2, tolerances);
                // 必须以"变了几个下标"为准：列名缺失时 changedColumns 可能是空的，但行长度不同仍是差异
                if (!changedIndices.isEmpty()) {
                    log.debug("Creating modified diff row with changed columns1: {}, columns2: {} for PK: {}",
                            changedColumns1, changedColumns2, primaryKey);
                    // Normalize values for output to ensure consistent formatting
                    List<Object> sourceValues = normalizeRowValues(row1, columnNames1, columnTypes1);
                    List<Object> targetValues = normalizeRowValues(row2, columnNames2, columnTypes2);
                    differences.add(DiffRow.modified(primaryKey, sourceValues, targetValues, columnNames1, columnNames2,
                            changedColumns1, changedColumns2));
                } else if (withinTolerance != null
                        && (!toleranceColumns1.isEmpty() || !toleranceColumns2.isEmpty())) {
                    // 整行没有真差异，但存在被容差吸收的列：留作报告样本，是否计数由调用方口径决定
                    withinTolerance.add(DiffRow.withinTolerance(primaryKey,
                            normalizeRowValues(row1, columnNames1, columnTypes1),
                            normalizeRowValues(row2, columnNames2, columnTypes2),
                            columnNames1, columnNames2, toleranceColumns1, toleranceColumns2, toleranceDeltas));
                }
            } else {
                /*
                 * 同键多行（重复键开放档 validateUniqueKeys=false）：多重集配对。
                 * 值相等的行互相抵消（数据相同必须报 0）；剩余行两侧按"规范化整行签名"
                 * 排序后按序 1:1 配对为 MISMATCH（含 changedColumns）；配不上的行按侧报
                 * SM/TM。每行带 occurrence、键带 keyMultiplicity，让"键不唯一"可见。
                 * 与 streaming 路径共用 MultisetRowPairing，保证两条计划结论一致。
                 */
                List<String> sourceSignatures = new ArrayList<>(relevantRows1.size());
                for (Object[] row : relevantRows1) {
                    sourceSignatures.add(standardRowSignature(row, columnNames1, columnTypes1));
                }
                List<String> targetSignatures = new ArrayList<>(relevantRows2.size());
                for (Object[] row : relevantRows2) {
                    targetSignatures.add(standardRowSignature(row, columnNames2, columnTypes2));
                }
                MultisetRowPairing.Outcome outcome = MultisetRowPairing.pair(sourceSignatures, targetSignatures);
                log.info("Duplicate-key group PK={}: sourceRows={}, targetRows={}, equal={}, pairedMismatch={},"
                                + " sourceOnly={}, targetOnly={}",
                        primaryKey, relevantRows1.size(), relevantRows2.size(), outcome.equalPairs.size(),
                        outcome.mismatchPairs.size(), outcome.sourceOnly.size(), outcome.targetOnly.size());

                for (int[] pair : outcome.mismatchPairs) {
                    Object[] row1 = relevantRows1.get(pair[0]);
                    Object[] row2 = relevantRows2.get(pair[1]);
                    List<String> changedColumns1 = new ArrayList<>();
                    List<String> changedColumns2 = new ArrayList<>();
                    findChangedColumnsWithDetails(row1, row2, columnNames1, columnNames2, changedColumns1,
                            changedColumns2, null, null, null, columnTypes1, columnTypes2, dataTypeHandler1,
                            dataTypeHandler2, tolerances);
                    DiffRow diff = DiffRow.modified(primaryKey,
                            normalizeRowValues(row1, columnNames1, columnTypes1),
                            normalizeRowValues(row2, columnNames2, columnTypes2),
                            columnNames1, columnNames2, changedColumns1, changedColumns2);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.sourceOccurrence[pair[0]], relevantRows1.size(), relevantRows2.size())));
                }
                for (int index : outcome.sourceOnly) {
                    DiffRow diff = DiffRow.removed(primaryKey, Arrays.asList(relevantRows1.get(index)), columnNames1);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.sourceOccurrence[index], relevantRows1.size(), relevantRows2.size())));
                }
                for (int index : outcome.targetOnly) {
                    DiffRow diff = DiffRow.added(primaryKey, Arrays.asList(relevantRows2.get(index)), columnNames2);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.targetOccurrence[index], relevantRows1.size(), relevantRows2.size())));
                }
            }
        }

        return differences;
    }

    /**
     * Find which columns have changed between two rows with separate tracking for
     * each table (type-aware).
     */
    private static List<Integer> findChangedColumnsWithDetails(
            Object[] row1, Object[] row2, List<String> columnNames1, List<String> columnNames2,
            List<String> changedColumns1, List<String> changedColumns2,
            List<String> toleranceColumns1, List<String> toleranceColumns2, Map<String, Double> toleranceDeltas,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1, DataTypeHandler dataTypeHandler2,
            Map<String, ColumnTolerance> tolerances) {

        List<Integer> changedColumnIndices = new ArrayList<>();

        log.debug("Finding changed columns between rows with lengths: {} and {}", row1.length, row2.length);

        if (row1.length != row2.length) {
            // If rows have different lengths, consider all columns as changed
            log.debug("Rows have different lengths, marking all columns as changed");
            for (int i = 0; i < Math.max(row1.length, row2.length); i++) {
                changedColumnIndices.add(i);
                if (i < row1.length && columnNames1 != null && i < columnNames1.size()) {
                    changedColumns1.add(columnNames1.get(i));
                }
                if (i < row2.length && columnNames2 != null && i < columnNames2.size()) {
                    changedColumns2.add(columnNames2.get(i));
                }
            }
            return changedColumnIndices;
        }

        for (int i = 0; i < row1.length; i++) {
            String columnName1 = (columnNames1 != null && i < columnNames1.size()) ? columnNames1.get(i) : null;
            String columnName2 = (columnNames2 != null && i < columnNames2.size()) ? columnNames2.get(i) : null;
            DataType dataType1 = columnTypes1 != null && columnName1 != null ? columnTypes1.get(columnName1) : null;
            DataType dataType2 = columnTypes2 != null && columnName2 != null ? columnTypes2.get(columnName2) : null;
            Object val1 = normalizeForComparison(row1[i], dataType1, dataTypeHandler1);
            Object val2 = normalizeForComparison(row2[i], dataType2, dataTypeHandler2);

            // CRITICAL: In NONE mode, rows contain database-normalized values (strings)
            // Direct string comparison is sufficient - no need for ValueNormalizer
            // This ensures consistency with database-side normalization
            if (!valuesEqual(val1, val2, preferExactType(dataType1, dataType2), tolerances, columnName1, columnName2,
                    dataType1, dataType2)) {
                changedColumnIndices.add(i);
                if (columnName1 != null) {
                    changedColumns1.add(columnName1);
                }
                if (columnName2 != null) {
                    changedColumns2.add(columnName2);
                }
                log.trace("Column {} values differ: '{}' vs '{}'", columnName1 != null ? columnName1 : i, val1, val2);
            } else if (toleranceColumns1 != null) {
                // 判等成功，但要区分"本来就相等"和"靠容差才相等"：后者才是报告里要单列的
                recordToleranceSuppressedColumn(val1, val2, columnName1, columnName2, dataType1, dataType2,
                        tolerances, toleranceColumns1, toleranceColumns2, toleranceDeltas);
            }
        }

        log.debug("Found {} changed columns: {}", changedColumnIndices.size(), changedColumnIndices);
        return changedColumnIndices;
    }

    /**
     * Compare two values with special handling for different types.
     */
    private static boolean valuesEqual(Object val1, Object val2) {
        return compareValuesInternal(val1, val2, null);
    }

    private static boolean valuesEqual(Object val1, Object val2, DataType dataType) {
        return compareValuesInternal(val1, val2, dataType);
    }

    /**
     * Value comparison with optional per-column numeric tolerance.
     *
     * <p>容差只在"浮点列 + 该列解析出容差 + 两侧都能当数值读"时生效。**必须接受字符串形态**：
     * checksum 路径的行值是 SQL 侧归一化后渲染的文本（如 MySQL 的 {@code FORMAT(...,6)}），
     * 原先只认 {@link Number}，导致容差配置解析成功却永远不参与判定（P1-5）。
     * 读不成数值（非数值文本、NULL 等）时回落到普通比较，不抛异常。
     */
    private static boolean valuesEqual(Object val1, Object val2, DataType dataType,
                                       Map<String, ColumnTolerance> tolerances,
                                       String columnName1, String columnName2,
                                       DataType dataType1, DataType dataType2) {
        ColumnTolerance tolerance = resolveTolerance(tolerances, columnName1, columnName2);
        DataType comparisonType = preferExactType(dataType1, dataType2);
        if (tolerance != null && !requiresExactNumericComparison(comparisonType)) {
            Double numeric1 = numericForTolerance(val1, dataType1, dataType2);
            Double numeric2 = numericForTolerance(val2, dataType1, dataType2);
            if (numeric1 != null && numeric2 != null) {
                boolean equal = tolerance.isEqual(numeric1, numeric2);
                log.trace("Comparing values with tolerance: '{}' vs '{}' = {}", val1, val2, equal);
                return equal;
            }
        }
        boolean result = compareValuesInternal(val1, val2, dataType);
        log.trace("Comparing values: '{}' vs '{}' = {}", val1, val2, result);
        return result;
    }

    /**
     * 记录"精确不等、但被容差判等"的列：只有这种列才会出现在报告的容差内样本里。
     * 数值相同的不同渲染（如 {@code 10.5} vs {@code 10.500}）不算，避免把纯格式差异当审计线索。
     */
    private static void recordToleranceSuppressedColumn(Object val1, Object val2,
                                                        String columnName1, String columnName2,
                                                        DataType dataType1, DataType dataType2,
                                                        Map<String, ColumnTolerance> tolerances,
                                                        List<String> toleranceColumns1,
                                                        List<String> toleranceColumns2,
                                                        Map<String, Double> toleranceDeltas) {
        ColumnTolerance tolerance = resolveTolerance(tolerances, columnName1, columnName2);
        if (tolerance == null || requiresExactNumericComparison(preferExactType(dataType1, dataType2))) {
            return;
        }
        Double numeric1 = numericForTolerance(val1, dataType1, dataType2);
        Double numeric2 = numericForTolerance(val2, dataType1, dataType2);
        if (numeric1 == null || numeric2 == null || numeric1.doubleValue() == numeric2.doubleValue()) {
            return;
        }
        if (!tolerance.isEqual(numeric1, numeric2)) {
            return;
        }
        String column = columnName1 != null ? columnName1 : columnName2;
        if (columnName1 != null && !toleranceColumns1.contains(columnName1)) {
            toleranceColumns1.add(columnName1);
        }
        if (columnName2 != null && toleranceColumns2 != null && !toleranceColumns2.contains(columnName2)) {
            toleranceColumns2.add(columnName2);
        }
        if (toleranceDeltas != null && column != null) {
            toleranceDeltas.put(column, Math.abs(numeric1 - numeric2));
        }
    }

    /**
     * 容差比较可用的数值：Number 直接可用；文本只有在**已知是浮点列**时才转成数值。
     * schema 不可用时列类型是 UNKNOWN，此时把 "12.34" 当数值会顺带放宽 DECIMAL 列
     * （DECIMAL 语义要求精确比较），所以文本形态必须有明确的浮点类型背书。
     */
    private static Double numericForTolerance(Object value, DataType type1, DataType type2) {
        if (value instanceof Number) {
            return toToleranceDouble(value);
        }
        // 任一侧确知是浮点即可：两侧 schema 可能只有一侧可用，按 preferExactType 取单侧会让结论随机
        if (value instanceof CharSequence && (isFloatingType(type1) || isFloatingType(type2))) {
            return toToleranceDouble(value);
        }
        return null;
    }

    private static boolean isFloatingType(DataType type) {
        return type == DataType.FLOAT || type == DataType.DOUBLE || type == DataType.REAL;
    }

    /**
     * 把数值或数值文本转成 double；读不出来返回 null（由调用方回落普通比较）。
     * SQL 归一化会把 DOUBLE 渲染成 "10.500000"，这是容差在 checksum 路径失效的直接原因。
     */
    static Double toToleranceDouble(Object value) {
        if (value instanceof Number) {
            double parsed = ((Number) value).doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                double parsed = Double.parseDouble(text);
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ColumnTolerance resolveTolerance(Map<String, ColumnTolerance> tolerances,
                                                    String columnName1, String columnName2) {
        if (tolerances == null || tolerances.isEmpty()) {
            return null;
        }
        ColumnTolerance tolerance = null;
        if (columnName1 != null) {
            tolerance = tolerances.get(columnName1.toLowerCase(java.util.Locale.ROOT));
        }
        if (tolerance == null && columnName2 != null) {
            tolerance = tolerances.get(columnName2.toLowerCase(java.util.Locale.ROOT));
        }
        return tolerance;
    }

    /**
     * Internal method to compare two values with special handling for different
     * types.
     */
    private static boolean compareValuesInternal(Object val1, Object val2, DataType dataType) {
        // Handle null cases
        if (val1 == null && val2 == null) {
            return true;
        }
        if (val1 == null || val2 == null) {
            return false;
        }

        // 定点数/整数必须精确比较：原来的相对容差（1e-6）会把大数的小数差异
        // （如 1000000.1 vs 1000000.2）判成相等，属于静默漏报。
        // 只有浮点类型保留容差，用于吸收不同引擎对同一 DOUBLE 的二进制表示差异。
        if (val1 instanceof Number && val2 instanceof Number) {
            if (requiresExactNumericComparison(dataType)) {
                return compareNumbersExactly((Number) val1, (Number) val2);
            }
            return compareNumericValues((Number) val1, (Number) val2);
        }

        // Handle string comparison with normalization
        if (val1 instanceof String || val2 instanceof String) {
            String str1 = normalizeStringValue(val1);
            String str2 = normalizeStringValue(val2);
            return str1.equals(str2);
        }

        // Handle temporal types
        if (val1 instanceof java.time.Instant && val2 instanceof java.time.Instant) {
            return val1.equals(val2);
        }

        if (val1 instanceof java.sql.Timestamp && val2 instanceof java.sql.Timestamp) {
            long time1 = ((java.sql.Timestamp) val1).getTime();
            long time2 = ((java.sql.Timestamp) val2).getTime();
            return time1 == time2;
        }

        if (val1 instanceof java.util.Date && val2 instanceof java.util.Date) {
            long time1 = ((java.util.Date) val1).getTime();
            long time2 = ((java.util.Date) val2).getTime();
            return time1 == time2;
        }

        // Handle boolean types
        if (val1 instanceof Boolean && val2 instanceof Boolean) {
            return val1.equals(val2);
        }

        // Handle binary data
        if (val1 instanceof byte[] && val2 instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) val1, (byte[]) val2);
        }

        // Default comparison
        return Objects.equals(val1, val2);
    }

    private static Object normalizeForComparison(Object value, DataType dataType,
            DataTypeHandler dataTypeHandler) {
        if (dataTypeHandler == null) {
            return value;
        }
        return dataTypeHandler.normalizeValueForComparison(value,
                dataType != null ? dataType : DataType.UNKNOWN);
    }

    /**
     * Compare numeric values with appropriate tolerance.
     */
    /** 两侧类型不一致时优先选"需要精确比较"的那一侧（例如 source INT / target DECIMAL）。 */
    private static DataType preferExactType(DataType left, DataType right) {
        if (requiresExactNumericComparison(left)) {
            return left;
        }
        if (requiresExactNumericComparison(right)) {
            return right;
        }
        return left != null ? left : right;
    }

    private static boolean requiresExactNumericComparison(DataType dataType) {
        if (dataType == null) {
            return false;
        }
        switch (dataType) {
            case DECIMAL:
            case NUMERIC:
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                return true;
            default:
                return false;
        }
    }

    private static boolean compareNumbersExactly(Number num1, Number num2) {
        try {
            return toBigDecimal(num1).compareTo(toBigDecimal(num2)) == 0;
        } catch (NumberFormatException e) {
            // 非有限浮点（NaN/Infinity）落到近似比较，避免误判
            return compareNumericValues(num1, num2);
        }
    }

    private static java.math.BigDecimal toBigDecimal(Number number) {
        if (number instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) number;
        }
        if (number instanceof java.math.BigInteger) {
            return new java.math.BigDecimal((java.math.BigInteger) number);
        }
        if (number instanceof Long || number instanceof Integer
                || number instanceof Short || number instanceof Byte) {
            return java.math.BigDecimal.valueOf(number.longValue());
        }
        return new java.math.BigDecimal(number.toString());
    }

    private static boolean compareNumericValues(Number num1, Number num2) {
        // Handle integer types exactly
        if (num1 instanceof Long || num2 instanceof Long ||
                num1 instanceof Integer || num2 instanceof Integer ||
                num1 instanceof Short || num2 instanceof Short ||
                num1 instanceof Byte || num2 instanceof Byte) {
            return num1.longValue() == num2.longValue();
        }

        // Handle floating point types with tolerance
        double d1 = num1.doubleValue();
        double d2 = num2.doubleValue();

        // Use relative tolerance for floating point comparison
        // Tolerance of 1e-6 allows for typical decimal precision differences (up to
        // 0.0001% relative error)
        // This accommodates differences like 24970.810011 vs 24970.81 (difference of
        // 0.000011)
        double tolerance = 1e-6;
        double diff = Math.abs(d1 - d2);
        double scale = Math.max(Math.max(Math.abs(d1), Math.abs(d2)), 1.0);

        return diff / scale <= tolerance;
    }

    /**
     * Normalize string values for consistent comparison.
     */
    private static String normalizeStringValue(Object value) {
        if (value == null) {
            return "";
        }

        String str = value.toString().trim();

        // JSON 值的紧凑规范化：不同引擎渲染同一 JSON 的空格策略不同
        // （mysql/{“a”: 1} vs trino json_format/{“a”:1}），语义等价的 JSON
        // 必须比较紧凑形式。只在 JSON 结构可解析时应用。
        if (str.length() >= 2 && ((str.startsWith("{") && str.endsWith("}"))
                || (str.startsWith("[") && str.endsWith("]")))) {
            String compact = compactJson(str);
            if (compact != null) {
                return compact;
            }
        }

        return str;
    }

    /**
     * 语义等价的 JSON 必须比较规范形：键排序 + 紧凑分隔符。
     * 直接复用 {@link ValueNormalizer#canonicalJson(String)}——此前这里用
     * {@code readTree} + {@code writeValueAsString}，只压空格不排键序，
     * 导致 {"a":1,"b":2} 与 {"b":2,"a":1} 被误报为 MISMATCH。
     */
    private static String compactJson(String str) {
        try {
            return ValueNormalizer.canonicalJson(str);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize all values in a row for consistent output formatting.
     * CRITICAL: In NONE mode, rows already contain database-normalized values (strings).
     * This method now simply returns the values as-is, since normalization
     * has already been done at the database level.
     *
     * @param row         The row to normalize
     * @param columnNames Column names
     * @param columnTypes Column data types
     * @return List of values (already normalized by database)
     */
    /**
     * 同键多行配对用的整行签名。必须按类型归一化后再拼：两侧对同一个 DECIMAL 可能给出
     * 不同 scale（25.0000 vs 25）或不同 JDBC 形态（BigDecimal vs String），直接 toString
     * 会让"数据相同"的两行签名不同，配对后误报成 MISMATCH。
     */
    private static String standardRowSignature(Object[] row, List<String> columnNames,
            Map<String, DataType> columnTypes) {
        List<String> normalized = new ArrayList<>(row.length);
        for (int i = 0; i < row.length; i++) {
            String columnName = columnNames != null && i < columnNames.size() ? columnNames.get(i) : null;
            DataType dataType = columnTypes != null && columnName != null ? columnTypes.get(columnName) : null;
            normalized.add(ValueNormalizer.normalizeValue(row[i],
                    dataType == null ? DataType.UNKNOWN : dataType));
        }
        return MultisetRowPairing.signature(normalized);
    }

    private static List<Object> normalizeRowValues(
            Object[] row,
            List<String> columnNames,
            Map<String, DataType> columnTypes) {

        // CRITICAL: Rows already contain database-normalized values
        // No need for additional normalization with ValueNormalizer
        List<Object> normalizedValues = new ArrayList<>(row.length);
        for (Object value : row) {
            normalizedValues.add(value);
        }
        return normalizedValues;
    }

}
