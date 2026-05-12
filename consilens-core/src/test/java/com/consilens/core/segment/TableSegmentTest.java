package com.consilens.core.segment;

import com.consilens.connector.api.model.TablePath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Test for TableSegment functionality.
 */
public class TableSegmentTest {

    @Test
    public void testTableSegmentCreation() {
        TablePath tablePath = TablePath.of("test_schema", "test_table");
        List<String> keyColumns = Arrays.asList("id");
        List<String> extraColumns = Arrays.asList("name", "age");

        TableSegment segment = TableSegment.builder()
                .tablePath(tablePath)
                .keyColumns(keyColumns)
                .extraColumns(extraColumns)
                .build();

        assertEquals(tablePath, segment.getTablePath());
        assertEquals(keyColumns, segment.getKeyColumns());
        assertEquals(extraColumns, segment.getExtraColumns());
    }

    @Test
    public void testRelevantColumns() {
        List<String> keyColumns = Arrays.asList("id");
        List<String> extraColumns = Arrays.asList("name", "age");

        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(keyColumns)
                .extraColumns(extraColumns)
                .updateColumn(Optional.of("updated_at"))
                .build();

        List<String> relevantColumns = segment.getRelevantColumns();

        assertEquals(4, relevantColumns.size());
        assertTrue(relevantColumns.contains("id"));
        assertTrue(relevantColumns.contains("name"));
        assertTrue(relevantColumns.contains("age"));
        assertTrue(relevantColumns.contains("updated_at"));
    }

    @Test
    public void testIsBounded() {
        TableSegment unboundedSegment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .build();

        assertFalse(unboundedSegment.isBounded());

        TableSegment boundedSegment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(1000)))
                .build();

        assertTrue(boundedSegment.isBounded());
    }

    @Test
    public void testApproximateSize() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(1000)))
                .build();

        long estimatedSize = segment.approximateSize();
        assertTrue(estimatedSize > 0);
        // Should be approximately 999 (1000 - 1)
        assertEquals(999, estimatedSize);
    }

    @Test
    public void testApproximateSizeWithStringKey() {
        // Test that string keys return -1 (unknown size)
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("record_id"))
                .minKey(Optional.of(Arrays.asList("REC0000000001")))
                .maxKey(Optional.of(Arrays.asList("REC0000010000")))
                .build();

        long estimatedSize = segment.approximateSize();
        // Should return -1 for non-numeric keys
        assertEquals(-1, estimatedSize);
    }

    @Test
    public void testApproximateSizeReturnsLongMaxValueOnOverflow() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id_part1", "id_part2"))
                .minKey(Optional.of(Arrays.asList(0L, 0L)))
                .maxKey(Optional.of(Arrays.asList(Long.MAX_VALUE, 3L)))
                .build();

        assertEquals(Long.MAX_VALUE, segment.approximateSize());
    }

    @Test
    public void testChooseCheckpoints() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(0)))
                .maxKey(Optional.of(Arrays.asList(100)))
                .build();

        List<List<Object>> checkpoints = segment.chooseCheckpoints(5);
        assertEquals(5, checkpoints.size());

        // Verify checkpoints are within bounds
        for (List<Object> checkpoint : checkpoints) {
            Object value = checkpoint.get(0);
            assertTrue(value instanceof Number);
            double doubleValue = ((Number) value).doubleValue();
            assertTrue(doubleValue > 0 && doubleValue < 100);
        }
    }

    @Test
    public void testSegmentByCheckpoints() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(0)))
                .maxKey(Optional.of(Arrays.asList(100)))
                .build();

        List<List<Object>> checkpoints = Arrays.asList(
                Arrays.asList(25),
                Arrays.asList(50),
                Arrays.asList(75));

        List<TableSegment> segments = segment.segmentByCheckpoints(checkpoints);
        assertEquals(4, segments.size()); // 3 checkpoints create 4 segments

        // Verify segments have correct bounds
        assertEquals(Arrays.asList(0), segments.get(0).getMinKey().get());
        assertEquals(Arrays.asList(25), segments.get(0).getMaxKey().get());

        assertEquals(Arrays.asList(25), segments.get(1).getMinKey().get());
        assertEquals(Arrays.asList(50), segments.get(1).getMaxKey().get());

        assertEquals(Arrays.asList(50), segments.get(2).getMinKey().get());
        assertEquals(Arrays.asList(75), segments.get(2).getMaxKey().get());

        assertEquals(Arrays.asList(75), segments.get(3).getMinKey().get());
        assertEquals(Arrays.asList(100), segments.get(3).getMaxKey().get());
    }

    @Test
    public void testBuildWhereClause() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id", "type"))
                .extraColumns(Arrays.asList("name"))
                .minKey(Optional.of(Arrays.asList(1, "A")))
                .maxKey(Optional.of(Arrays.asList(100, "Z")))
                .whereClause(Optional.of("status = 'active'"))
                .build();

        String whereClause = segment.buildWhereClause();

        assertNotNull(whereClause);
        assertTrue(whereClause.contains("(id > 1 OR (id = 1 AND type >= 'A'))"));
        assertTrue(whereClause.contains("(id < 100 OR (id = 100 AND type < 'Z'))"));
        assertTrue(whereClause.contains("status = 'active'"));
    }

    @Test
    public void testBuildWhereClauseCanIncludeUpperBound() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("biz_date", "status"))
                .minKey(Optional.of(Arrays.asList("2026-05-01", "active")))
                .maxKey(Optional.of(Arrays.asList("2026-05-01", "pending")))
                .upperBoundInclusive(true)
                .build();

        String whereClause = segment.buildWhereClause();

        assertTrue(whereClause.contains("(biz_date > '2026-05-01' OR (biz_date = '2026-05-01' AND status >= 'active'))"));
        assertTrue(whereClause.contains("(biz_date < '2026-05-01' OR (biz_date = '2026-05-01' AND status <= 'pending'))"));
    }

    @Test
    public void testFinalSegmentKeepsInclusiveUpperBoundOnlyOnTailSegment() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(0)))
                .maxKey(Optional.of(Arrays.asList(100)))
                .upperBoundInclusive(true)
                .build();

        List<TableSegment> segments = segment.segmentByCheckpoints(Arrays.asList(
                Arrays.asList(25),
                Arrays.asList(50),
                Arrays.asList(75)));

        assertFalse(segments.get(0).isUpperBoundInclusive());
        assertFalse(segments.get(1).isUpperBoundInclusive());
        assertFalse(segments.get(2).isUpperBoundInclusive());
        assertTrue(segments.get(3).isUpperBoundInclusive());
    }

    @Test
    public void testBuildWhereClauseRejectsUnsafeCustomClause() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(10)))
                .whereClause(Optional.of("status = 'active'; DROP TABLE user_data"))
                .build();

        assertThrows(IllegalArgumentException.class, segment::buildWhereClause);
    }

    @Test
    public void testBuildWhereClauseAllowsSafeCustomClause() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(10)))
                .whereClause(Optional.of("status = 'active' AND region IN ('cn', 'us') AND archived IS NULL"))
                .build();

        String whereClause = segment.buildWhereClause();

        assertTrue(whereClause.contains("status = 'active'"));
        assertTrue(whereClause.contains("region IN ('cn', 'us')"));
        assertTrue(whereClause.contains("archived IS NULL"));
    }

    @Test
    public void testValidation() {
        // Valid segment should not throw
        assertDoesNotThrow(() -> {
            TableSegment.builder()
                    .tablePath(TablePath.of("test_table"))
                    .keyColumns(Arrays.asList("id"))
                    .build()
                    .validate();
        });

        // Invalid segment with no key columns should throw
        assertThrows(IllegalArgumentException.class, () -> {
            TableSegment.builder()
                    .tablePath(TablePath.of("test_table"))
                    .keyColumns(Arrays.asList())
                    .build()
                    .validate();
        });

        // Invalid segment with null database should throw
        // Removed as we now allow null database for testing purposes
        /*
         * assertThrows(IllegalArgumentException.class, () -> {
         * TableSegment.builder()
         * .tablePath(TablePath.of("test_table"))
         * .keyColumns(Arrays.asList("id"))
         * .database(null)
         * .build()
         * .validate();
         * });
         */
    }

    @Test
    public void testWithBounds() {
        TableSegment original = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id"))
                .build();

        TableSegment withBounds = original.withBounds(Arrays.asList(10), Arrays.asList(100));

        assertEquals(Arrays.asList(10), withBounds.getMinKey().get());
        assertEquals(Arrays.asList(100), withBounds.getMaxKey().get());
    }
}
