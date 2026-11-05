package com.consilens.core.segment;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TableSchema;
import com.consilens.connector.api.model.TablePath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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

        // Date-looking strings are emitted as ANSI literals so Presto/Trino strict
        // type checking accepts the comparison.
        assertTrue(whereClause.contains("(biz_date > DATE '2026-05-01' OR (biz_date = DATE '2026-05-01' AND status >= 'active'))"));
        assertTrue(whereClause.contains("(biz_date < DATE '2026-05-01' OR (biz_date = DATE '2026-05-01' AND status <= 'pending'))"));
    }

    @Test
    public void testBuildWhereClauseWithLocalDateKeyUsesAnsiLiteral() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("created"))
                .minKey(Optional.of(Arrays.asList(LocalDate.of(2026, 5, 1))))
                .maxKey(Optional.of(Arrays.asList(LocalDate.of(2026, 5, 31))))
                .build();

        String whereClause = segment.buildWhereClause();

        assertTrue(whereClause.contains("created >= DATE '2026-05-01'"));
        assertTrue(whereClause.contains("created < DATE '2026-05-31'"));
    }

    @Test
    public void testBuildWhereClauseQuotesNumericLookingVarcharKey() {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("code", column("code", DataType.VARCHAR));
        TableSchema schema = TableSchema.of(TablePath.of("test_table"), columns);

        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("code"))
                .schema(Optional.of(schema))
                .minKey(Optional.of(Collections.singletonList("00123")))
                .maxKey(Optional.of(Collections.singletonList("00200")))
                .build();

        String whereClause = segment.buildWhereClause();

        assertTrue(whereClause.contains("code >= '00123'"));
        assertTrue(whereClause.contains("code < '00200'"));
    }

    /**
     * 单键列边界为 NULL（整列全 NULL 时 MIN/MAX 为 NULL）时不得静默丢弃谓词：
     * 闭合上界（{@code <= NULL}）改写为 {@code IS NULL}，开区间上界（{@code < NULL}）恒假。
     */
    @Test
    public void testBuildWhereClauseWithNullSingleColumnBounds() {
        TableSegment inclusive = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("code"))
                .minKey(Optional.of(Collections.singletonList(null)))
                .maxKey(Optional.of(Collections.singletonList(null)))
                .upperBoundInclusive(true)
                .build();

        assertEquals("code IS NULL", inclusive.buildWhereClause());

        TableSegment exclusive = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("code"))
                .minKey(Optional.of(Collections.singletonList(null)))
                .maxKey(Optional.of(Collections.singletonList(null)))
                .build();

        assertEquals("1 = 0", exclusive.buildWhereClause());
    }

    @Test
    public void testBuildWhereClauseUnquotesNumericKeyFromDriverString() {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        columns.put("id", column("id", DataType.BIGINT));
        TableSchema schema = TableSchema.of(TablePath.of("test_table"), columns);

        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("id"))
                .schema(Optional.of(schema))
                .minKey(Optional.of(Collections.singletonList("123")))
                .maxKey(Optional.of(Collections.singletonList("456")))
                .build();

        String whereClause = segment.buildWhereClause();

        assertTrue(whereClause.contains("id >= 123"));
        assertTrue(whereClause.contains("id < 456"));
        assertFalse(whereClause.contains("id >= '123'"));
    }

    /**
     * 只有上界的分段（尾扫 below-min 场景）必须生成上界谓词。
     * 旧实现把整个键区间块挂在 minKey.isPresent() 上，导致该段退化为整表扫描。
     */
    @Test
    public void testBuildWhereClauseWithOnlyUpperBound() {
        TableSegment inclusive = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("id"))
                .maxKey(Optional.of(Collections.singletonList("100")))
                .upperBoundInclusive(true)
                .build();

        assertEquals("id <= 100", inclusive.buildWhereClause());

        TableSegment exclusive = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("id"))
                .maxKey(Optional.of(Collections.singletonList("100")))
                .build();

        assertEquals("id < 100", exclusive.buildWhereClause());
    }

    @Test
    public void testBuildWhereClauseWithOnlyLowerBound() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Collections.singletonList("id"))
                .minKey(Optional.of(Collections.singletonList("10")))
                .build();

        assertEquals("id >= 10", segment.buildWhereClause());
    }

    @Test
    public void testBuildWhereClauseCompositeWithOnlyUpperBound() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id", "type"))
                .maxKey(Optional.of(Arrays.asList(100, "Z")))
                .upperBoundInclusive(true)
                .build();

        assertEquals("(id < 100 OR (id = 100 AND type <= 'Z'))", segment.buildWhereClause());
    }

    @Test
    public void testBuildWhereClauseCompositeWithOnlyLowerBound() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id", "type"))
                .minKey(Optional.of(Arrays.asList(1, "A")))
                .build();

        assertEquals("(id > 1 OR (id = 1 AND type >= 'A'))", segment.buildWhereClause());
    }

    @Test
    public void testCompositeLowerBoundWithNullLastComponentIncludesPrefixRows() {
        // last 维为 NULL 时它就是该前缀下的最小值：>= (1, NULL) 应包含所有 id=1 的行
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id", "type"))
                .minKey(Optional.of(Arrays.asList(1, null)))
                .build();

        assertEquals("(id > 1 OR (id = 1))", segment.buildWhereClause());
    }

    @Test
    public void testCompositeLowerBoundWithNullFirstComponentKeepsNullRows() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("id", "type"))
                .minKey(Optional.of(Arrays.asList(null, "B")))
                .build();

        assertEquals("(id IS NOT NULL OR (id IS NULL AND type >= 'B'))", segment.buildWhereClause());
    }

    private ColumnInfo column(String name, DataType type) {
        return ColumnInfo.builder()
                .name(name)
                .type(type)
                .nullable(true)
                .ordinalPosition(1)
                .precision(Optional.empty())
                .scale(Optional.empty())
                .maxLength(Optional.empty())
                .defaultValue(Optional.empty())
                .collation(Optional.empty())
                .primaryKey(false)
                .uniqueKey(false)
                .comment(Optional.empty())
                .build();
    }

    @Test
    public void testBuildWhereClauseWithLocalDateKeyUsesQuotedLiteralForSqlServer() {
        DatabaseDialect sqlServerDialect = (DatabaseDialect) java.lang.reflect.Proxy.newProxyInstance(
                DatabaseDialect.class.getClassLoader(),
                new Class<?>[]{DatabaseDialect.class},
                (proxy, method, args) -> "usesAnsiTemporalLiterals".equals(method.getName()) ? false : null);
        DatabaseAdapter sqlServerAdapter = (DatabaseAdapter) java.lang.reflect.Proxy.newProxyInstance(
                DatabaseAdapter.class.getClassLoader(),
                new Class<?>[]{DatabaseAdapter.class},
                (proxy, method, args) -> {
                    if ("getConnectorType".equals(method.getName())) {
                        return "sqlserver";
                    }
                    if ("getDialect".equals(method.getName())) {
                        return sqlServerDialect;
                    }
                    return null;
                });

        TableSegment segment = TableSegment.builder()
                .database(sqlServerAdapter)
                .tablePath(TablePath.of("test_table"))
                .keyColumns(Arrays.asList("created"))
                .minKey(Optional.of(Arrays.asList(LocalDate.of(2026, 5, 1))))
                .maxKey(Optional.of(Arrays.asList(LocalDate.of(2026, 5, 31))))
                .build();

        String whereClause = segment.buildWhereClause();

        // SQL Server has no ANSI DATE literal; quoted strings are implicitly converted.
        assertTrue(whereClause.contains("created >= '2026-05-01'"));
        assertTrue(whereClause.contains("created < '2026-05-31'"));
        assertFalse(whereClause.contains("DATE '"));
        assertFalse(whereClause.contains("TIMESTAMP '"));
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
