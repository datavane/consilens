package com.consilens.core.compare.executor;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.record.RecordKey;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorRecordDifferTest {

    @Test
    void shouldCompareOrderedScannersInStreamingMode() {
        CompareSegment source = segment("source_orders", List.of(
                record("1", "A"),
                record("2", "B"),
                record("4", "D")));
        CompareSegment target = segment("target_orders", List.of(
                record("1", "A"),
                record("3", "C"),
                record("4", "E")));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build());

        assertEquals(3L, result.getStatistics().getSourceRowCount());
        assertEquals(3L, result.getStatistics().getTargetRowCount());
        assertEquals(1L, result.getStatistics().getSourceMissingCount());
        assertEquals(1L, result.getStatistics().getTargetMissingCount());
        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals(3L, result.getStatistics().getTotalDifferences());
        assertEquals(3, result.getDifferences().size());
    }

    @Test
    void shouldPropagateBudgetExceededInsteadOfWrappingIt() {
        CompareSegment source = segment("source_orders", List.of(
                record("1", "A"),
                record("2", "B")));
        CompareSegment target = segment("target_orders", List.of(
                record("1", "A"),
                record("2", "B")));

        assertThrows(com.consilens.core.exception.BudgetExceededException.class,
                () -> new ConnectorRecordDiffer().diff(
                        source,
                        target,
                        CompareExecutionSettings.builder()
                                .validateUniqueKeys(true)
                                .maxRowsScanned(1L)
                                .build()));
    }

    @Test
    void shouldExcludeColumnsInStreamingMode() {
        CompareSegment source = segment("source_orders",
                ComparisonSpec.builder().fields(List.of("value")).exclude(List.of("value")).build(),
                List.of(record("1", "A")));
        CompareSegment target = segment("target_orders",
                ComparisonSpec.builder().fields(List.of("value")).exclude(List.of("value")).build(),
                List.of(record("1", "B")));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build());

        assertEquals(0L, result.getStatistics().getMismatchCount());
        assertEquals(0L, result.getStatistics().getTotalDifferences());
    }

    @Test
    void shouldIgnoreDerivedHashColumnsWhenFieldsAreNotExplicit() {
        SchemaDescriptor schema = schema(List.of("id", "value", "row_hash"));
        CompareSegment source = segment("source_orders", schema, null,
                List.of(record(Map.of("id", "1", "value", "A", "row_hash", "mysql-hash"))));
        CompareSegment target = segment("target_orders", schema, null,
                List.of(record(Map.of("id", "1", "value", "A", "row_hash", "postgres-hash"))));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build());

        assertEquals(0L, result.getStatistics().getTotalDifferences());
        assertEquals(0, result.getDifferences().size());
    }

    @Test
    void shouldIgnoreKnownDerivedChecksumColumnsWhenFieldsAreNotExplicit() {
        SchemaDescriptor schema = schema(List.of(
                "id",
                "value",
                "checksum",
                "row_checksum",
                "record_checksum",
                "record_hash",
                "row_md5",
                "consilens_checksum",
                "consilens_row_hash"));
        CompareSegment source = segment("source_orders", schema, null,
                List.of(record(Map.of(
                        "id", "1",
                        "value", "A",
                        "checksum", "source-checksum",
                        "row_checksum", "source-row-checksum",
                        "record_checksum", "source-record-checksum",
                        "record_hash", "source-record-hash",
                        "row_md5", "source-md5",
                        "consilens_checksum", "source-consilens-checksum",
                        "consilens_row_hash", "source-consilens-row-hash"))));
        CompareSegment target = segment("target_orders", schema, null,
                List.of(record(Map.of(
                        "id", "1",
                        "value", "A",
                        "checksum", "target-checksum",
                        "row_checksum", "target-row-checksum",
                        "record_checksum", "target-record-checksum",
                        "record_hash", "target-record-hash",
                        "row_md5", "target-md5",
                        "consilens_checksum", "target-consilens-checksum",
                        "consilens_row_hash", "target-consilens-row-hash"))));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build());

        assertEquals(0L, result.getStatistics().getTotalDifferences());
        assertEquals(0, result.getDifferences().size());
    }

    @Test
    void shouldCompareDerivedHashColumnsWhenFieldsAreExplicit() {
        SchemaDescriptor schema = schema(List.of("id", "value", "row_hash"));
        CompareSegment source = segment("source_orders", schema,
                ComparisonSpec.builder().fields(List.of("row_hash")).build(),
                List.of(record(Map.of("id", "1", "value", "A", "row_hash", "mysql-hash"))));
        CompareSegment target = segment("target_orders", schema,
                ComparisonSpec.builder().fields(List.of("row_hash")).build(),
                List.of(record(Map.of("id", "1", "value", "A", "row_hash", "postgres-hash"))));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build());

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals(1L, result.getStatistics().getTotalDifferences());
        assertEquals(List.of("row_hash"), result.getDifferences().get(0).getChangedColumns1());
        assertEquals(List.of("row_hash"), result.getDifferences().get(0).getChangedColumns2());
    }

    @Test
    void shouldAllowDuplicateKeysWhenValidationIsDisabled() {
        CompareSegment source = segment("source_orders", List.of(record("1", "A"), record("1", "B")));
        CompareSegment target = segment("target_orders", List.of(record("1", "A")));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(false)
                        .build());

        // 开放档按多重集语义：值相等的行互相抵消，仅 (1,B) 无对侧行 → 1 条差异
        // （与 checksum/streaming 的多重集配对结论一致）。
        assertEquals(1L, result.getStatistics().getTotalDifferences());
        assertEquals(1L, result.getStatistics().getTargetMissingCount());
    }

    @Test
    void shouldFailOnDuplicateKeysWhenValidationIsEnabled() {
        CompareSegment source = segment("source_orders", List.of(record("1", "A"), record("1", "B")));
        CompareSegment target = segment("target_orders", List.of(record("1", "A")));

        assertThrows(com.consilens.connector.api.ConnectorException.class, () -> new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .build()));
    }

    @Test
    void shouldAllowDiffCountAtConfiguredLimit() {
        CompareSegment source = segment("source_orders", List.of(record("1", "A")));
        CompareSegment target = segment("target_orders", List.of());

        DiffResult result = new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .maxDifferences(1L)
                        .build());

        assertEquals(1L, result.getStatistics().getTotalDifferences());
    }

    @Test
    void shouldFailWhenDiffCountExceedsConfiguredLimit() {
        CompareSegment source = segment("source_orders", List.of(record("1", "A"), record("2", "B")));
        CompareSegment target = segment("target_orders", List.of());

        assertThrows(com.consilens.connector.api.ConnectorException.class, () -> new ConnectorRecordDiffer().diff(
                source,
                target,
                CompareExecutionSettings.builder()
                        .validateUniqueKeys(true)
                        .maxDifferences(1L)
                        .build()));
    }

    @Test
    void shouldKeepRawValuesInDiffOutput() {
        SchemaDescriptor schema = SchemaDescriptor.builder()
                .fields(List.of(
                        FieldDescriptor.builder().name("id").canonicalType("varchar").build(),
                        FieldDescriptor.builder().name("value").canonicalType("decimal").build()))
                .fieldMap(Map.of(
                        "id", FieldDescriptor.builder().name("id").canonicalType("varchar").build(),
                        "value", FieldDescriptor.builder().name("value").canonicalType("decimal").build()))
                .build();
        CompareSegment source = segment("source_orders", schema, null,
                List.of(record(Map.of("id", "1", "value", new BigDecimal("1.2300")))));
        CompareSegment target = segment("target_orders", schema, null,
                List.of(record(Map.of("id", "1", "value", new BigDecimal("2.3400")))));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source, target, CompareExecutionSettings.builder().validateUniqueKeys(true).build());

        assertEquals(List.of("1", new BigDecimal("1.2300")), result.getDifferences().get(0).getSourceValues().get());
        assertEquals(List.of("1", new BigDecimal("2.3400")), result.getDifferences().get(0).getTargetValues().get());
    }

    @Test
    void shouldUseConfiguredDecimalScaleForStreamingValues() {
        com.consilens.connector.api.model.SchemaDescriptor schema = com.consilens.connector.api.model.SchemaDescriptor.builder()
                .fields(List.of(
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("id").canonicalType("varchar").build(),
                        com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("value").canonicalType("decimal").build()))
                .fieldMap(Map.of(
                        "id", com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("id").canonicalType("varchar").build(),
                        "value", com.consilens.connector.api.model.FieldDescriptor.builder()
                                .name("value").canonicalType("decimal").build()))
                .build();
        CompareSegment source = segment("source_orders", schema, null, List.of(
                record(Map.of("id", "1", "value", new BigDecimal("0.123456")))));
        CompareSegment target = segment("target_orders", schema, null, List.of(
                record(Map.of("id", "1", "value", new BigDecimal("0.123457")))));

        DiffResult atDefault = new ConnectorRecordDiffer().diff(source, target,
                CompareExecutionSettings.builder().build());
        assertEquals(0L, atDefault.getStatistics().getTotalDifferences(),
                "默认 4 位小数会把第 5/6 位差异抹平");

        DiffResult atConfigured = new ConnectorRecordDiffer().diff(source, target,
                CompareExecutionSettings.builder().decimalScale(6).build());
        assertEquals(1L, atConfigured.getStatistics().getMismatchCount(),
                "配置 6 位后应看到第 6 位差异");
    }

    @Test
    void shouldKeepHighPrecisionDecimalKeysDistinctInStreamingMode() {
        SchemaDescriptor schema = SchemaDescriptor.builder()
                .fields(List.of(
                        FieldDescriptor.builder().name("id").canonicalType("decimal").build(),
                        FieldDescriptor.builder().name("value").canonicalType("varchar").build()))
                .fieldMap(Map.of(
                        "id", FieldDescriptor.builder().name("id").canonicalType("decimal").build(),
                        "value", FieldDescriptor.builder().name("value").canonicalType("varchar").build()))
                .build();
        CompareSegment source = segment("source_orders", schema, null, List.of(
                record(Map.of("id", new BigDecimal("1.00001"), "value", "A")),
                record(Map.of("id", new BigDecimal("1.00002"), "value", "A"))));
        CompareSegment target = segment("target_orders", schema, null, List.of(
                record(Map.of("id", new BigDecimal("1.00002"), "value", "A"))));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source, target, CompareExecutionSettings.builder().build());

        assertEquals(1L, result.getStatistics().getTotalDifferences());
        assertEquals(1L, result.getStatistics().getTargetMissingCount());
        assertEquals(0L, result.getStatistics().getMismatchCount());
    }

    private CompareSegment segment(String tableName, List<CanonicalRecord> records) {
        return segment(tableName, ComparisonSpec.builder().fields(List.of("value")).build(), records);
    }

    private CompareSegment segment(String tableName, ComparisonSpec comparisons, List<CanonicalRecord> records) {
        return segment(tableName, schema(List.of("id", "value")), comparisons, records);
    }

    @Test
    void shouldMatchKeyColumnsCaseInsensitively() {
        SchemaDescriptor schema = schema(List.of("ID", "VALUE"));
        CompareSegment source = segment("source_orders", schema, null,
                List.of(record(Map.of("ID", "1", "VALUE", "A"))), List.of("id"));
        CompareSegment target = segment("target_orders", schema, null,
                List.of(record(Map.of("ID", "1", "VALUE", "A"))), List.of("id"));

        DiffResult result = new ConnectorRecordDiffer().diff(
                source, target, CompareExecutionSettings.builder().validateUniqueKeys(true).build());

        assertEquals(0L, result.getStatistics().getTotalDifferences(),
                "大写元数据列名 + 小写 key 配置不应产生重复列/假差异");
    }

    @Test
    void shouldRejectMissingComparisonColumn() {
        SchemaDescriptor schema = schema(List.of("id", "value"));
        CompareSegment source = segment("source_orders", schema,
                ComparisonSpec.builder().fields(List.of("missing")).build(),
                List.of(record("1", "A")));
        CompareSegment target = segment("target_orders", schema,
                ComparisonSpec.builder().fields(List.of("missing")).build(),
                List.of(record("1", "A")));

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> new ConnectorRecordDiffer().diff(source, target,
                        CompareExecutionSettings.builder().build()));
        assertTrue(exception.getMessage().contains("not present in the input schema"));
    }

    @Test
    void shouldRejectMissingKeyColumn() {
        SchemaDescriptor schema = schema(List.of("id", "value"));
        CompareSegment source = segment("source_orders", schema, null,
                List.of(record("1", "A")), List.of("missing"));
        CompareSegment target = segment("target_orders", schema, null,
                List.of(record("1", "A")), List.of("missing"));

        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> new ConnectorRecordDiffer().diff(source, target,
                        CompareExecutionSettings.builder().build()));
        assertTrue(exception.getMessage().contains("not present in the input schema"));
    }

    private CompareSegment segment(String tableName,
                                   SchemaDescriptor schema,
                                   ComparisonSpec comparisons,
                                   List<CanonicalRecord> records) {
        return segment(tableName, schema, comparisons, records, List.of("id"));
    }

    private CompareSegment segment(String tableName,
                                   SchemaDescriptor schema,
                                   ComparisonSpec comparisons,
                                   List<CanonicalRecord> records,
                                   List<String> keys) {
        ResourceLocator resource = ResourceLocator.builder()
                .type("table")
                .name(tableName)
                .build();
        return CompareSegment.builder()
                .dataset(new TestDatasetHandle(resource, schema, records))
                .resource(resource)
                .keySpec(KeySpec.builder().fields(keys).build())
                .comparisons(comparisons)
                .schema(schema)
                .build();
    }

    private CanonicalRecord record(String id, String value) {
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        values.put("id", CanonicalValue.builder().type("varchar").value(id).build());
        values.put("value", CanonicalValue.builder().type("varchar").value(value).build());
        return new TestRecord(RecordKey.builder().parts(List.of(id)).build(), values);
    }

    private CanonicalRecord record(Map<String, Object> rawValues) {
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawValues.entrySet()) {
            values.put(entry.getKey(), CanonicalValue.builder().type("varchar").value(entry.getValue()).build());
        }
        Object key = rawValues.containsKey("id")
                ? rawValues.get("id") : rawValues.values().iterator().next();
        return new TestRecord(RecordKey.builder().parts(List.of(key)).build(), values);
    }

    private SchemaDescriptor schema(List<String> columns) {
        List<FieldDescriptor> fields = new ArrayList<>();
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (String column : columns) {
            FieldDescriptor field = FieldDescriptor.builder().name(column).canonicalType("varchar").build();
            fields.add(field);
            fieldMap.put(column, field);
        }
        return SchemaDescriptor.builder()
                .fields(fields)
                .fieldMap(fieldMap)
                .build();
    }

    private static final class TestDatasetHandle implements DatasetHandle {

        private final ResourceLocator resource;
        private final SchemaDescriptor schema;
        private final List<CanonicalRecord> records;

        private TestDatasetHandle(ResourceLocator resource, SchemaDescriptor schema, List<CanonicalRecord> records) {
            this.resource = resource;
            this.schema = schema;
            this.records = records;
        }

        @Override
        public ResourceLocator getResource() {
            return resource;
        }

        @Override
        public DatasetMetadata getMetadata() {
            return DatasetMetadata.builder().logicalName(resource.getName()).build();
        }

        @Override
        public SchemaDescriptor getSchema() {
            return schema;
        }

        @Override
        public Optional<RecordScanner> getRecordScanner() {
            return Optional.of(segment -> new TestIterator(records));
        }

        @Override
        public Optional<SplitPlanner> getSplitPlanner() {
            return Optional.empty();
        }

        @Override
        public Optional<HashProvider> getHashProvider() {
            return Optional.empty();
        }

        @Override
        public Optional<KeyLookupProvider> getKeyLookupProvider() {
            return Optional.empty();
        }

        @Override
        public Optional<SnapshotProvider> getSnapshotProvider() {
            return Optional.empty();
        }

        @Override
        public Optional<FilterPushdownProvider> getFilterPushdownProvider() {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }

    private static final class TestIterator implements CloseableIterator<CanonicalRecord> {

        private final Iterator<CanonicalRecord> iterator;

        private TestIterator(List<CanonicalRecord> records) {
            this.iterator = new ArrayList<>(records).iterator();
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public CanonicalRecord next() {
            return iterator.next();
        }

        @Override
        public void close() {
        }
    }

    private static final class TestRecord implements CanonicalRecord {

        private final RecordKey key;
        private final Map<String, CanonicalValue> values;

        private TestRecord(RecordKey key, Map<String, CanonicalValue> values) {
            this.key = key;
            this.values = values;
        }

        @Override
        public RecordKey getKey() {
            return key;
        }

        @Override
        public Map<String, CanonicalValue> getValues() {
            return values;
        }
    }
}
