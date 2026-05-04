package com.consilens.core.compare.executor;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private CompareSegment segment(String tableName, List<CanonicalRecord> records) {
        return segment(tableName, ComparisonSpec.builder().fields(List.of("value")).build(), records);
    }

    private CompareSegment segment(String tableName, ComparisonSpec comparisons, List<CanonicalRecord> records) {
        ResourceLocator resource = ResourceLocator.builder()
                .type("table")
                .name(tableName)
                .build();
        SchemaDescriptor schema = SchemaDescriptor.builder()
                .fields(List.of(
                        FieldDescriptor.builder().name("id").canonicalType("varchar").build(),
                        FieldDescriptor.builder().name("value").canonicalType("varchar").build()))
                .fieldMap(Map.of(
                        "id", FieldDescriptor.builder().name("id").canonicalType("varchar").build(),
                        "value", FieldDescriptor.builder().name("value").canonicalType("varchar").build()))
                .build();
        return CompareSegment.builder()
                .dataset(new TestDatasetHandle(resource, schema, records))
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
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
