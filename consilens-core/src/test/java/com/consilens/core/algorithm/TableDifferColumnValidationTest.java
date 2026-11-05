package com.consilens.core.algorithm;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.SchemaCompatibility;
import com.consilens.core.segment.TableSegment;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-7：列数不等的两侧不能"按位置"硬配，否则第 i 列会映射到另一侧不同的列，
 * 产生伪造的 MISMATCH 或吞掉真实差异。这里锁定 fail-fast 行为。
 */
class TableDifferColumnValidationTest {

    @Test
    void extraColumnCountMismatchFailsFast() {
        TableSegment left = segment(List.of("id"), List.of("name"));
        TableSegment right = segment(List.of("id"), List.of("name", "age"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExposedDiffer().validate(left, right));

        assertTrue(error.getMessage().contains("Extra (non-key) column count must match"), error.getMessage());
    }

    @Test
    void keyColumnCountMismatchFailsFast() {
        TableSegment left = segment(List.of("id"), List.of("name"));
        TableSegment right = segment(List.of("id", "tenant"), List.of("name"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExposedDiffer().validate(left, right));

        assertTrue(error.getMessage().contains("Key columns count must match"), error.getMessage());
    }

    @Test
    void equalColumnCountsPassValidation() {
        TableSegment left = segment(List.of("id"), List.of("name"));
        TableSegment right = segment(List.of("id"), List.of("name"));

        new ExposedDiffer().validate(left, right);
    }

    @Test
    void nullableKeyWithoutFilterFailsFast() {
        TableSegment left = nullableKeySegment("db.t");
        TableSegment right = nullableKeySegment("db.t2");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExposedDiffer().validate(left, right));

        assertTrue(error.getMessage().contains("is nullable"), error.getMessage());
        assertTrue(error.getMessage().contains("allowNullableKeys=true"), error.getMessage());
    }

    @Test
    void nullableKeyAllowedWhenExplicitlyConfigured() {
        TableSegment left = nullableKeySegment("db.t");
        TableSegment right = nullableKeySegment("db.t2");

        // 显式允许可空键：不再前置拒绝，交给 IS NULL/归一化路径处理
        new ExposedDiffer(true).validate(left, right);
    }

    @Test
    void nullableKeyWithNotNullFilterPasses() {
        // 错误信息推荐用 IS NOT NULL 过滤；校验必须认可这种修复，否则提示自相矛盾
        TableSegment left = nullableKeySegment("db.t").toBuilder()
                .whereClause(java.util.Optional.of("pk_id IS NOT NULL")).build();
        TableSegment right = nullableKeySegment("db.t2").toBuilder()
                .whereClause(java.util.Optional.of("pk_id IS NOT NULL")).build();

        new ExposedDiffer().validate(left, right);
    }

    @Test
    void nullableKeyWithDisjunctiveNotNullFilterStillFails() {
        // OR 的另一支仍可能命中 NULL 键；只有合取条件才能保证 NULL 被排除
        TableSegment left = nullableKeySegment("db.t").toBuilder()
                .whereClause(java.util.Optional.of("pk_id IS NOT NULL OR status = 'active'")).build();
        TableSegment right = nullableKeySegment("db.t2").toBuilder()
                .whereClause(java.util.Optional.of("pk_id IS NOT NULL OR status = 'active'")).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExposedDiffer().validate(left, right));

        assertTrue(error.getMessage().contains("is nullable"), error.getMessage());
    }

    @Test
    void shouldDetectNullableKeyThatRangePredicatesCannotCover() {
        // 可空键 + 没有排除 NULL 的过滤条件：MIN/MAX 与区间谓词会漏掉 NULL 行
        assertTrue(TableDiffer.mayContainNullKeys(nullableKeySegment("db.t")));

        // 过滤条件显式排除 NULL 后可安全分段
        assertFalse(TableDiffer.mayContainNullKeys(nullableKeySegment("db.t").toBuilder()
                .whereClause(java.util.Optional.of("pk_id IS NOT NULL")).build()));

        // 非空键列不受影响
        assertFalse(TableDiffer.mayContainNullKeys(
                segmentWithSchema("db.a", "id", DataType.BIGINT, "name", DataType.VARCHAR)));
    }

    @Test
    void shouldReportNormalizationForCompatibleTypeFamilies() {
        TableSegment left = segmentWithSchema("db.a", "id", DataType.BIGINT, "name", DataType.VARCHAR);
        TableSegment right = segmentWithSchema("db.b", "id", DataType.BIGINT, "name", DataType.TEXT);

        SchemaCompatibility report = new ExposedDiffer().validate(left, right);

        assertEquals(SchemaCompatibility.Status.COMPATIBLE_WITH_NORMALIZATION, report.getStatus());
        assertEquals(SchemaCompatibility.Action.DIRECT, report.getColumns().get(0).getAction());
        assertEquals(SchemaCompatibility.Action.NORMALIZE, report.getColumns().get(1).getAction());
        assertTrue(report.getColumns().get(1).getReason().contains("normalization"));
    }

    @Test
    void shouldReportIncompatibleForDifferentTypeFamilies() {
        TableSegment left = segmentWithSchema("db.a", "id", DataType.BIGINT, "created", DataType.VARCHAR);
        TableSegment right = segmentWithSchema("db.b", "id", DataType.BIGINT, "created", DataType.TIMESTAMP);

        SchemaCompatibility report = new ExposedDiffer().validate(left, right);

        assertEquals(SchemaCompatibility.Status.INCOMPATIBLE, report.getStatus());
        assertEquals(SchemaCompatibility.Action.INCOMPATIBLE, report.getColumns().get(1).getAction());
    }

    private TableSegment segmentWithSchema(String path,
                                           String keyName, DataType keyType,
                                           String extraName, DataType extraType) {
        TablePath tablePath = TablePath.of(path);
        ColumnInfo key = ColumnInfo.builder()
                .name(keyName)
                .type(keyType)
                .nullable(false)
                .ordinalPosition(1)
                .build();
        ColumnInfo extra = ColumnInfo.builder()
                .name(extraName)
                .type(extraType)
                .nullable(true)
                .ordinalPosition(2)
                .build();
        return TableSegment.builder()
                .tablePath(tablePath)
                .keyColumns(List.of(keyName))
                .extraColumns(List.of(extraName))
                .schema(java.util.Optional.of(TableSchema.of(tablePath,
                        Map.of(keyName, key, extraName, extra))))
                .build();
    }

    private TableSegment nullableKeySegment(String path) {
        TablePath tablePath = TablePath.of(path);
        com.consilens.connector.api.model.ColumnInfo key =
                com.consilens.connector.api.model.ColumnInfo.builder()
                        .name("pk_id")
                        .type(com.consilens.connector.api.model.DataType.BIGINT)
                        .nullable(true)
                        .ordinalPosition(1)
                        .build();
        return TableSegment.builder()
                .tablePath(tablePath)
                .keyColumns(List.of("pk_id"))
                .extraColumns(List.of("name"))
                .schema(java.util.Optional.of(com.consilens.connector.api.model.TableSchema.of(
                        tablePath, java.util.Map.of("pk_id", key))))
                .build();
    }

    private TableSegment segment(List<String> keys, List<String> extras) {
        return TableSegment.builder()
                .tablePath(TablePath.of("db", "t"))
                .keyColumns(keys)
                .extraColumns(extras)
                .build();
    }

    private static final class ExposedDiffer extends TableDiffer {
        ExposedDiffer() {
            this(false);
        }

        ExposedDiffer(boolean allowNullableKeys) {
            super(new DifferConfig(4, 1000L, com.consilens.common.enums.ChecksumAlgorithm.CONCAT,
                    com.consilens.common.enums.LocalCompareMode.FULL, null, 100_000L, false,
                    com.consilens.core.algorithm.ValueNormalizer.DEFAULT_DECIMAL_SCALE, allowNullableKeys));
        }

        SchemaCompatibility validate(TableSegment left, TableSegment right) {
            return validateAndAdjustColumns(left, right);
        }

        @Override
        protected CompletableFuture<DiffResult> diffTablesRoot(TableSegment table1, TableSegment table2,
                                                               com.consilens.core.diff.InfoTreeRecorder recorder) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        protected CompletableFuture<Void> diffSegments(TableSegment table1, TableSegment table2,
                                                       com.consilens.core.diff.InfoTreeRecorder recorder,
                                                       long maxRows, int level) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
