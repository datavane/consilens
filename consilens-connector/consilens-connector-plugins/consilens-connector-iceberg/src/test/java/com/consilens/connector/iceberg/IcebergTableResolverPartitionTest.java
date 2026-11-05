package com.consilens.connector.iceberg;

import com.consilens.connector.api.ConnectorException;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level tests for equality delete applicability, including partition spec
 * evolution where spec IDs drift but partition shapes still match.
 */
class IcebergTableResolverPartitionTest {

    private static final Types.StructType NAME_SPEC = Types.StructType.of(
            Types.NestedField.required(1, "name", Types.StringType.get()));
    private static final Types.StructType RENAMED_NAME_SPEC = Types.StructType.of(
            Types.NestedField.required(1, "region", Types.StringType.get()));
    private static final Types.StructType TWO_FIELD_SPEC = Types.StructType.of(
            Types.NestedField.required(1, "name", Types.StringType.get()),
            Types.NestedField.required(2, "dt", Types.StringType.get()));

    @Test
    void sameSpecAppliesByPartitionValues() {
        IcebergTableResolver.DataFileInfo data = data(0, NAME_SPEC, "alice", 1L);
        IcebergTableResolver.EqualityDelete delete = delete(0, NAME_SPEC, "alice", 2L);
        assertEquals(true, IcebergTableResolver.equalityDeleteApplies(data, delete));
    }

    @Test
    void sameSequenceDoesNotApply() {
        IcebergTableResolver.DataFileInfo data = data(0, NAME_SPEC, "alice", 2L);
        IcebergTableResolver.EqualityDelete delete = delete(0, NAME_SPEC, "alice", 2L);
        assertEquals(false, IcebergTableResolver.equalityDeleteApplies(data, delete));
    }

    @Test
    void differentValuesDoNotApply() {
        IcebergTableResolver.DataFileInfo data = data(0, NAME_SPEC, "alice", 1L);
        IcebergTableResolver.EqualityDelete delete = delete(0, NAME_SPEC, "bob", 2L);
        assertEquals(false, IcebergTableResolver.equalityDeleteApplies(data, delete));
    }

    @Test
    void evolvedSpecWithSameShapeApplies() {
        IcebergTableResolver.DataFileInfo data = data(0, NAME_SPEC, "alice", 1L);
        IcebergTableResolver.EqualityDelete delete = delete(1, RENAMED_NAME_SPEC, "alice", 2L);
        assertEquals(true, IcebergTableResolver.equalityDeleteApplies(data, delete));
    }

    @Test
    void evolvedSpecWithDifferentShapeFailsFast() {
        IcebergTableResolver.DataFileInfo data = data(0, NAME_SPEC, "alice", 1L);
        IcebergTableResolver.EqualityDelete delete = delete(1, TWO_FIELD_SPEC, null, 2L);
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> IcebergTableResolver.equalityDeleteApplies(data, delete));
        assertEquals(true, exception.getMessage().contains("different partition type"));
    }

    private IcebergTableResolver.DataFileInfo data(int specId, Types.StructType partitionType,
                                                   String name, long sequence) {
        return new IcebergTableResolver.DataFileInfo("file:///data/data-1.parquet", specId,
                partitionType, structLike(name), sequence, FileFormat.PARQUET);
    }

    @Test
    void datePartitionMatchesIsoLiteral() {
        // Iceberg 的 DATE 分区值是"距 1970 的天数"：19723 = 2024-01-01
        assertTrue(IcebergTableResolver.partitionValueMatches(19723, "2024-01-01",
                Types.DateType.get()));
        assertFalse(IcebergTableResolver.partitionValueMatches(19723, "2024-01-02",
                Types.DateType.get()));
    }

    @Test
    void timestampPartitionMatchesIsoLiteral() {
        long micros = java.time.LocalDateTime.parse("2024-01-01T10:00:00")
                .toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L;

        assertTrue(IcebergTableResolver.partitionValueMatches(micros, "2024-01-01 10:00:00",
                Types.TimestampType.withoutZone()));
        assertFalse(IcebergTableResolver.partitionValueMatches(micros, "2024-01-01 10:00:01",
                Types.TimestampType.withoutZone()));
    }

    @Test
    void decimalPartitionComparesNumerically() {
        assertTrue(IcebergTableResolver.partitionValueMatches(new java.math.BigDecimal("1.50"),
                "1.5", Types.DecimalType.of(10, 2)));
    }

    @Test
    void integerBooleanAndStringPartitionsCompareByValue() {
        assertTrue(IcebergTableResolver.partitionValueMatches(5, " 5 ", Types.IntegerType.get()));
        assertFalse(IcebergTableResolver.partitionValueMatches(5, "6", Types.IntegerType.get()));
        assertTrue(IcebergTableResolver.partitionValueMatches(7L, "7", Types.LongType.get()));
        assertTrue(IcebergTableResolver.partitionValueMatches(true, "TRUE",
                Types.BooleanType.get()));
        assertTrue(IcebergTableResolver.partitionValueMatches("eu", "eu",
                Types.StringType.get()));
    }

    @Test
    void nullPartitionOnlyMatchesNullLiteral() {
        assertTrue(IcebergTableResolver.partitionValueMatches(null, "null",
                Types.StringType.get()));
        assertFalse(IcebergTableResolver.partitionValueMatches(null, "eu",
                Types.StringType.get()));
    }

    @Test
    void datePartitionMatchesMidnightTimestampLiteral() {
        // 分区列是 DATE（epoch day），过滤值写了完整时间戳也应该命中同一天
        assertTrue(IcebergTableResolver.partitionValueMatches(19723, "2024-01-01T00:00", org.apache.iceberg.types.Types.DateType.get()));
    }

    @Test
    void timestampPartitionMatchesDateOnlyLiteral() {
        long micros = java.time.LocalDateTime.parse("2024-01-01T00:00:00")
                .toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L;

        assertTrue(IcebergTableResolver.partitionValueMatches(micros, "2024-01-01", org.apache.iceberg.types.Types.TimestampType.withoutZone()));
        assertTrue(IcebergTableResolver.partitionValueMatches(micros, "2024-01-01T00:00", org.apache.iceberg.types.Types.TimestampType.withoutZone()));
        assertFalse(IcebergTableResolver.partitionValueMatches(micros, "2024-01-02", org.apache.iceberg.types.Types.TimestampType.withoutZone()));
    }

    @Test
    void yamlDatePartitionFilterBecomesIsoText() {
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("partition.dt", new java.util.Date(1704067200000L));
        options.put("partition.region", "eu");
        com.consilens.connector.api.model.ResourceLocator resource =
                new com.consilens.connector.api.model.ResourceLocator();
        resource.setOptions(options);

        java.util.Map<String, String> filters = IcebergDatasetHandle.partitionFilters(resource);

        // YAML 的 2024-01-01 会被解析成 Date，必须还原成 ISO 时间文本；
        // 裁剪成纯日期会让 TIMESTAMP 分区匹配不到任何文件，"纯日期"的解释交给按类型的比较逻辑
        assertEquals("2024-01-01T00:00", filters.get("dt"));
        assertEquals("eu", filters.get("region"));
    }

    private IcebergTableResolver.EqualityDelete delete(int specId, Types.StructType partitionType,
                                                       String name, long sequence) {
        return new IcebergTableResolver.EqualityDelete("file:///data/delete-1.parquet", specId,
                partitionType, structLike(name), sequence, List.of(1), FileFormat.PARQUET);
    }

    private StructLike structLike(String name) {
        if (name == null) {
            return null;
        }
        return new StructLike() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(int pos, Class<T> javaClass) {
                return (T) name;
            }

            @Override
            public <T> void set(int pos, T value) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
