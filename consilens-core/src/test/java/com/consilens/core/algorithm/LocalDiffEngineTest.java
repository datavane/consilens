package com.consilens.core.algorithm;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import com.consilens.connector.api.model.DataType;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.sql.Timestamp;

/**
 * Test for LocalDiffEngine functionality.
 */
public class LocalDiffEngineTest {

        @Test
        public void testFindDifferencesWithEmptyData() {
                List<Object[]> rows1 = Collections.emptyList();
                List<Object[]> rows2 = Collections.emptyList();
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("name");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertTrue(differences.isEmpty());
        }

        @Test
        public void compositeKeysContainingSeparatorAreNotMerged() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { "a\u0001b", "c", "x" });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { "a", "b\u0001c", "x" });
                List<String> keyColumns = Arrays.asList("k1", "k2");
                List<String> extraColumns = Collections.singletonList("v");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(2, differences.size());
                assertEquals(1L, differences.stream()
                        .filter(row -> row.getOperation() == DiffOperation.SOURCE_MISSING).count());
                assertEquals(1L, differences.stream()
                        .filter(row -> row.getOperation() == DiffOperation.TARGET_MISSING).count());
        }

        @Test
        public void testFindDifferencesWithIdenticalData() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertTrue(differences.isEmpty());
        }

        @Test
        public void testFindDifferencesWithAddedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                // 只在目标端存在的行 = 源端缺失（与 DiffRow.added / ChecksumDiffer.added 同义）
                assertEquals(DiffOperation.SOURCE_MISSING, differences.get(0).getOperation());
                assertEquals(2, differences.get(0).getTargetValues().get().get(0)); // id = 2
        }

        @Test
        public void testFindDifferencesWithRemovedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.TARGET_MISSING, differences.get(0).getOperation());
                assertEquals(2, differences.get(0).getSourceValues().get().get(0)); // id = 2
        }

        @Test
        public void testFindDifferencesWithUpdatedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 26 }, // age changed
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        }

        @Test
        public void testFindDifferencesWithComplexKey() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", "Alice", 25 },
                                new Object[] { 1, "B", "Alice", 25 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", "Alice", 26 }, // age changed
                                new Object[] { 2, "A", "Bob", 30 } // different compound key
                );
                List<String> keyColumns = Arrays.asList("id", "type");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                // Should detect: 1 updated row (1,A) + 1 added row (2,A) + 1 removed row (1,B)
                assertEquals(3, differences.size());
        }

        @Test
        public void testFindDifferencesWithNormalization() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", "25.0000", "2023-01-01 00:00:00" });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", "25.0000", "2023-01-01 00:00:00" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "score", "date");

                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("name", DataType.VARCHAR);
                columnTypes.put("score", DataType.DECIMAL);
                columnTypes.put("date", DataType.DATETIME);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                // LocalDiffEngine compares database-side normalized values.
                // If the rows are already canonicalized, no differences should remain.
                assertTrue(differences.isEmpty(), "Should find no differences after normalization");
        }

        @Test
        public void testJsonWithDifferentKeyOrderIsTreatedAsEqual() {
                List<Object[]> rows1 = Collections.<Object[]>singletonList(
                                new Object[] { 1, "{\"a\":1,\"b\":{\"y\":2,\"x\":[1,2]}}" });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { 1, "{\"b\":{\"x\":[1,2],\"y\":2},\"a\":1}" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("payload");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("payload", DataType.JSON);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertTrue(differences.isEmpty(),
                                "键序/空白不同但语义等价的 JSON 不应报差异");
        }

        @Test
        public void testJsonWithDifferentValueStillReportsMismatch() {
                List<Object[]> rows1 = Collections.<Object[]>singletonList(
                                new Object[] { 1, "{\"a\":1}" });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { 1, "{\"a\":2}" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("payload");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("payload", DataType.JSON);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        }

        @Test
        public void testDecimalDifferencesWithinRelativeToleranceAreStillReported() {
                // 1e6 量级下 0.1 的相对差约 1e-7，旧实现按 1e-6 相对容差会判等（静默漏报）
                List<Object[]> rows1 = Collections.<Object[]>singletonList(
                                new Object[] { 1, new java.math.BigDecimal("1000000.1") });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { 1, new java.math.BigDecimal("1000000.2") });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("amount");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.BIGINT);
                columnTypes.put("amount", DataType.DECIMAL);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertEquals(1, differences.size(), "DECIMAL 差异不能因为相对容差被吞掉");
        }

        @Test
        public void testDistinctHighPrecisionDecimalKeysAreNotMerged() {
                // 1.00001 与 1.00002 在 4 位小数形态下都是 "1.0000"；若按该形态分组，
                // 两个不同主键会并成一组、值又都是 "A"，差异会被整体吃掉。
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { new java.math.BigDecimal("1.00001"), "A" },
                                new Object[] { new java.math.BigDecimal("1.00002"), "A" });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { new java.math.BigDecimal("1.00002"), "A" });
                List<String> keyColumns = Collections.singletonList("amount");
                List<String> extraColumns = Collections.singletonList("flag");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("amount", DataType.DECIMAL);
                columnTypes.put("flag", DataType.VARCHAR);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertEquals(1, differences.size(), "只剩 1.00001 这条单侧缺失，不能被键合并吃掉");
                assertEquals(DiffOperation.TARGET_MISSING, differences.get(0).getOperation());
        }

        @Test
        public void testDuplicateKeyRowsWithDifferentDecimalScalePairAsEqual() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", new java.math.BigDecimal("25.0000") },
                                new Object[] { 1, "A", new java.math.BigDecimal("30.0") });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", new java.math.BigDecimal("25") },
                                new Object[] { 1, "A", new java.math.BigDecimal("30.00") });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "amount");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("name", DataType.VARCHAR);
                columnTypes.put("amount", DataType.DECIMAL);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertTrue(differences.isEmpty(), "同一键的多行在 scale 不同但数值相等时应配对为相等");
        }

        @Test
        public void testDecimalScaleVariantsAreEqual() {
                List<Object[]> rows1 = Collections.<Object[]>singletonList(
                                new Object[] { 1, new java.math.BigDecimal("25.0000") });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { 1, new java.math.BigDecimal("25") });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("amount");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.BIGINT);
                columnTypes.put("amount", DataType.DECIMAL);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertTrue(differences.isEmpty(), "25.0000 与 25 数值相等，scale 不同不算差异");
        }

        @Test
        public void testFloatingPointToleranceIsKept() {
                List<Object[]> rows1 = Collections.<Object[]>singletonList(
                                new Object[] { 1, 24970.810011d });
                List<Object[]> rows2 = Collections.<Object[]>singletonList(
                                new Object[] { 1, 24970.81d });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("ratio");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.BIGINT);
                columnTypes.put("ratio", DataType.DOUBLE);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertTrue(differences.isEmpty(), "浮点仍保留 1e-6 相对容差，吸收二进制表示差异");
        }

        @Test
        public void testTimestampDifferenceOfEightHoursIsNotIgnored() {
                List<Object[]> rows1 = Collections.singletonList(
                                new Object[] { 1, Timestamp.valueOf("2023-01-01 00:00:00") });
                List<Object[]> rows2 = Collections.singletonList(
                                new Object[] { 1, Timestamp.valueOf("2023-01-01 08:00:00") });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("created_at");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("created_at", DataType.TIMESTAMP);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        }

        @Test
        public void testSortedKeysAtShardBoundary() {
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("name");

                // 两侧都按键升序输出，相邻分片在边界键 3 处重叠（前片尾部、后片头部各出现一次）。
                List<Object[]> shard1Rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "a" },
                                new Object[] { 2, "b" },
                                new Object[] { 3, "c" });
                List<Object[]> shard1Rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "a" },
                                new Object[] { 2, "b" },
                                new Object[] { 3, "c" });

                List<DiffRow> boundaryEqual = LocalDiffEngine.findDifferences(
                                shard1Rows1, shard1Rows2, keyColumns, extraColumns, keyColumns, extraColumns);
                assertTrue(boundaryEqual.isEmpty(), "边界键在两侧相同且值一致时不应产生差异");

                // 后片：边界键 3 在目标侧缺失，4 两侧相同 -> 只报 3 的 TARGET_MISSING。
                List<Object[]> shard2Rows1 = Arrays.<Object[]>asList(
                                new Object[] { 3, "c" },
                                new Object[] { 4, "d" });
                List<Object[]> shard2Rows2Missing = Collections.<Object[]>singletonList(new Object[] { 4, "d" });

                List<DiffRow> boundaryMissing = LocalDiffEngine.findDifferences(
                                shard2Rows1, shard2Rows2Missing, keyColumns, extraColumns, keyColumns, extraColumns);
                assertEquals(1, boundaryMissing.size());
                assertEquals(DiffOperation.TARGET_MISSING, boundaryMissing.get(0).getOperation());
                assertEquals(3, boundaryMissing.get(0).getPrimaryKey().get(0));

                // 边界键两侧都存在但值不同 -> MISMATCH（键相同不代表行相同）。
                List<Object[]> shard2Rows2Changed = Arrays.<Object[]>asList(
                                new Object[] { 3, "C" },
                                new Object[] { 4, "d" });
                List<DiffRow> boundaryMismatch = LocalDiffEngine.findDifferences(
                                shard2Rows1, shard2Rows2Changed, keyColumns, extraColumns, keyColumns, extraColumns);
                assertEquals(1, boundaryMismatch.size());
                assertEquals(DiffOperation.MISMATCH, boundaryMismatch.get(0).getOperation());
                assertEquals(3, boundaryMismatch.get(0).getPrimaryKey().get(0));
        }

        @Test
        public void shouldReportEveryDuplicateRowOnSingleSide() {
                List<Object[]> rows1 = Collections.emptyList();
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice" },
                                new Object[] { 1, "Alice" },
                                new Object[] { 1, "Alice" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("name");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(3, differences.size(), "重复键的每一行都必须单独报出，不能被去重压成一行");
                for (DiffRow row : differences) {
                        assertEquals(DiffOperation.SOURCE_MISSING, row.getOperation());
                }
                assertEquals(1, differences.get(0).getMetadata().get("occurrence"));
                assertEquals(2, differences.get(1).getMetadata().get("occurrence"));
                assertEquals(3, differences.get(2).getMetadata().get("occurrence"));
        }

        @Test
        public void shouldReportEveryDuplicateRowForCompositeKeys() {
                List<Object[]> rows1 = Collections.emptyList();
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "2026-01", "Alice" },
                                new Object[] { 1, "2026-01", "Alicia" },
                                new Object[] { 1, "2026-02", "Bob" });
                List<String> keyColumns = Arrays.asList("id", "month");
                List<String> extraColumns = Collections.singletonList("name");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(3, differences.size(), "复合键下同键两行也必须分别报出");
                for (DiffRow row : differences) {
                        assertEquals(DiffOperation.SOURCE_MISSING, row.getOperation());
                }
                assertEquals(1, differences.get(0).getMetadata().get("occurrence"));
                assertEquals(2, differences.get(1).getMetadata().get("occurrence"));
                assertEquals(1, differences.get(2).getMetadata().get("occurrence"));
        }

        @Test
        public void shouldUsePerSideColumnNamesForChangedColumns() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(new Object[] { 1, "Alice" });
                List<Object[]> rows2 = Arrays.<Object[]>asList(new Object[] { 1, "Alicia" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> sourceExtras = Collections.singletonList("name");
                List<String> targetExtras = Collections.singletonList("full_name");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, sourceExtras, keyColumns, targetExtras);

                assertEquals(1, differences.size());
                assertEquals(Collections.singletonList("name"), differences.get(0).getChangedColumns1());
                assertEquals(Collections.singletonList("full_name"), differences.get(0).getChangedColumns2());
        }
}
