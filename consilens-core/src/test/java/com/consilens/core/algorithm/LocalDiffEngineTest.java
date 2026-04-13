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
}
