package com.consilens.core.segment;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复合键边界含 NULL 时的区间谓词。
 *
 * <p>dq_cmp_edge_* 实测：分段下界取到 {@code [NULL, 's1']} 时引擎曾生成
 * {@code k1 > NULL OR (k1 = NULL AND k2 >= 's1')}，SQL 中与 NULL 比较恒为 UNKNOWN，
 * 该段查询返回 0 行，25 行的表被判成"空 vs 空"，漏报 27/28 条差异。
 */
public class TableSegmentCompositeNullBoundTest {

    private TableSegment segment(List<Object> minKey, List<Object> maxKey) {
        TablePath path = TablePath.fromString("dq_cmp_edge_a");
        TableSchema schema = new TableSchema(path, Map.of(
                "k1", ColumnInfo.of("k1", DataType.VARCHAR),
                "k2", ColumnInfo.of("k2", DataType.VARCHAR)));
        return TableSegment.builder()
                .tablePath(path)
                .keyColumns(List.of("k1", "k2"))
                .extraColumns(List.of("v_int"))
                .minKey(java.util.Optional.of(minKey))
                .maxKey(java.util.Optional.of(maxKey))
                .schema(java.util.Optional.of(schema))
                .build();
    }

    @Test
    public void nullLowerBoundIsRenderedWithIsNullSemantics() {
        String where = segment(java.util.Arrays.asList(null, "s1"),
                java.util.Arrays.asList("ABC", "s1")).buildWhereClause();

        assertFalse(where.contains("> NULL"), "不得生成与 NULL 的比较: " + where);
        assertFalse(where.contains("= NULL"), "不得生成与 NULL 的等值比较: " + where);
        assertTrue(where.contains("k1 IS NOT NULL"), "NULL 分量下界应改写为 IS NOT NULL: " + where);
        assertTrue(where.contains("k1 IS NULL"), "相等前缀中的 NULL 应改写为 IS NULL: " + where);
    }

    /**
     * 上界末分量为 NULL 且区间闭合（{@code <=}）时，按「NULL 排在最前」口径应为 IS NULL；
     * 直接丢弃谓词会把上界之外的行也纳入本段，漏掉 NULL 尾部行。
     */
    @Test
    public void nullInclusiveUpperBoundIsRenderedWithIsNullSemantics() {
        TableSegment segment = TableSegment.builder()
                .tablePath(TablePath.fromString("dq_cmp_edge_a"))
                .keyColumns(List.of("k1", "k2"))
                .minKey(java.util.Optional.of(java.util.Arrays.asList("ABC", "s1")))
                .maxKey(java.util.Optional.of(java.util.Arrays.asList("ABC", null)))
                .upperBoundInclusive(true)
                .build();

        String where = segment.buildWhereClause();

        assertFalse(where.contains("<= NULL"), "不得生成与 NULL 的比较: " + where);
        assertTrue(where.contains("k2 IS NULL"), "NULL 尾部上界（<=）应改写为 IS NULL: " + where);
    }

    @Test
    public void nonNullBoundsKeepPlainComparisons() {
        String where = segment(java.util.Arrays.asList("1", "s1"),
                java.util.Arrays.asList("9", "s1")).buildWhereClause();

        assertTrue(where.contains("k1 > '1'"), "非空边界保持原样: " + where);
        assertTrue(where.contains("k1 = '1' AND k2 >= 's1'"), "非空边界保持原样: " + where);
        assertFalse(where.contains("IS NULL"), "非空边界不应出现 IS NULL: " + where);
    }
}
