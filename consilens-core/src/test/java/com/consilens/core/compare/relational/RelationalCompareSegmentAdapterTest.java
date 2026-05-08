package com.consilens.core.compare.relational;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RelationalCompareSegmentAdapterTest {

    @Test
    void shouldUseSubqueryRelationForSqlResource() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("sql")
                .name("orders_sql")
                .path("SELECT id, name FROM orders;")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .schema(dataset.getSchema())
                .build();

        RelationalCompareSegmentAdapter.PreparedTableSegment prepared =
                RelationalCompareSegmentAdapter.toTableSegment(segment, CompareExecutionSettings.fromRequest(null));

        TableSegment tableSegment = prepared.getTableSegment();
        assertTrue(tableSegment.hasRelationSource());
        assertEquals("SELECT id, name FROM orders", tableSegment.getRelationFromSql());
        assertEquals(TablePath.of("orders_sql"), tableSegment.getTablePath());
        assertEquals(List.of("name"), tableSegment.getExtraColumns());
        assertFalse(tableSegment.getRelationFromSql().contains("CREATE TEMPORARY"));
        assertThrows(ConnectorException.class, dataset::getTablePath);
    }

    @Test
    void shouldExcludeColumnsFromAutomaticMatching() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("sql")
                .name("orders_sql")
                .path("SELECT id, name FROM orders")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .comparisons(ComparisonSpec.builder().exclude(List.of("name")).build())
                .schema(dataset.getSchema())
                .build();

        RelationalCompareSegmentAdapter.PreparedTableSegment prepared =
                RelationalCompareSegmentAdapter.toTableSegment(segment, CompareExecutionSettings.fromRequest(null));

        assertEquals(List.of(), prepared.getTableSegment().getExtraColumns());
    }

    @Test
    void shouldIgnoreDerivedHashColumnsFromAutomaticMatching() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("sql")
                .name("orders_sql")
                .path("SELECT id, name, row_hash FROM orders")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        SchemaDescriptor schema = SchemaDescriptor.builder()
                .fields(List.of(
                        FieldDescriptor.builder().name("id").canonicalType("bigint").build(),
                        FieldDescriptor.builder().name("name").canonicalType("VARCHAR").build(),
                        FieldDescriptor.builder().name("row_hash").canonicalType("VARCHAR").build()))
                .fieldMap(Map.of(
                        "id", FieldDescriptor.builder().name("id").canonicalType("bigint").build(),
                        "name", FieldDescriptor.builder().name("name").canonicalType("VARCHAR").build(),
                        "row_hash", FieldDescriptor.builder().name("row_hash").canonicalType("VARCHAR").build()))
                .build();
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .schema(schema)
                .build();

        RelationalCompareSegmentAdapter.PreparedTableSegment prepared =
                RelationalCompareSegmentAdapter.toTableSegment(segment, CompareExecutionSettings.fromRequest(null));

        assertEquals(List.of("name"), prepared.getTableSegment().getExtraColumns());
    }

    @Test
    void shouldHonorExplicitComparisonFieldsIncludingDerivedHashColumns() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("sql")
                .name("orders_sql")
                .path("SELECT id, name, row_hash FROM orders")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        SchemaDescriptor schema = schema("id", "name", "row_hash");
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .comparisons(ComparisonSpec.builder().fields(List.of("row_hash")).build())
                .schema(schema)
                .build();

        RelationalCompareSegmentAdapter.PreparedTableSegment prepared =
                RelationalCompareSegmentAdapter.toTableSegment(segment, CompareExecutionSettings.fromRequest(null));

        assertEquals(List.of("row_hash"), prepared.getTableSegment().getExtraColumns());
    }

    @Test
    void shouldRejectExplicitComparisonFieldsOverlappingKeys() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("table")
                .name("orders")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        CompareSegment segment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .comparisons(ComparisonSpec.builder().fields(List.of("id", "name")).build())
                .schema(dataset.getSchema())
                .build();

        assertThrows(ConnectorException.class,
                () -> RelationalCompareSegmentAdapter.toTableSegment(segment, CompareExecutionSettings.fromRequest(null)));
    }

    @Test
    void shouldApplyFilterAndSupportedSplitsToTableSegment() {
        ResourceLocator resource = ResourceLocator.builder()
                .type("table")
                .name("orders")
                .build();
        StubRelationalDataset dataset = new StubRelationalDataset(resource);
        CompareSegment keyRangeSegment = CompareSegment.builder()
                .dataset(dataset)
                .resource(resource)
                .keySpec(KeySpec.builder().fields(List.of("id")).build())
                .filter(PredicateSpec.builder().expression("id >= 10").build())
                .schema(dataset.getSchema())
                .split(KeyRangeSplit.builder()
                        .startKey(List.of(10L))
                        .endKey(List.of(20L))
                        .build())
                .build();
        CompareSegment offsetSegment = keyRangeSegment.toBuilder()
                .split(OffsetLimitSplit.builder()
                        .offset(100L)
                        .limit(50L)
                        .build())
                .build();

        TableSegment keyRange = RelationalCompareSegmentAdapter
                .toTableSegment(keyRangeSegment, CompareExecutionSettings.fromRequest(null))
                .getTableSegment();
        TableSegment offset = RelationalCompareSegmentAdapter
                .toTableSegment(offsetSegment, CompareExecutionSettings.fromRequest(null))
                .getTableSegment();

        assertEquals(Optional.of("id >= 10 AND id < 20 AND ((id >= 10))"), keyRange.getWhereClause());
        assertEquals(Optional.of(List.of(10L)), keyRange.getMinKey());
        assertEquals(Optional.of(List.of(20L)), keyRange.getMaxKey());
        assertTrue(offset.getLimitOffset().isPresent());
        assertEquals(50L, offset.getLimitOffset().get().getLimit());
        assertEquals(100L, offset.getLimitOffset().get().getOffset());
    }

    private SchemaDescriptor schema(String... names) {
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
        List<FieldDescriptor> fields = java.util.Arrays.stream(names)
                .map(name -> FieldDescriptor.builder().name(name).canonicalType("VARCHAR").build())
                .peek(field -> fieldMap.put(field.getName(), field))
                .collect(Collectors.toList());
        return SchemaDescriptor.builder()
                .fields(fields)
                .fieldMap(fieldMap)
                .build();
    }

    private static final class StubRelationalDataset implements DatasetHandle, RelationalDatasetSupport {

        private final ResourceLocator resource;
        private final DatabaseDialect dialect = mock(DatabaseDialect.class);
        private final SchemaDescriptor schema;

        private StubRelationalDataset(ResourceLocator resource) {
            this.resource = resource;
            FieldDescriptor id = FieldDescriptor.builder().name("id").canonicalType("bigint").build();
            FieldDescriptor name = FieldDescriptor.builder().name("name").canonicalType("VARCHAR").build();
            Map<String, FieldDescriptor> fields = new LinkedHashMap<>();
            fields.put("id", id);
            fields.put("name", name);
            this.schema = SchemaDescriptor.builder()
                    .fields(List.of(id, name))
                    .fieldMap(fields)
                    .build();
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
            return Optional.empty();
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

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public String getConnectorType() {
            return "mysql";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:mysql://localhost:3306/test";
        }

        @Override
        public String getUsername() {
            return "root";
        }

        @Override
        public DatabaseDialect getDialect() {
            return dialect;
        }

        @Override
        public TablePath getTablePath() {
            if ("sql".equalsIgnoreCase(resource.getType())) {
                throw new ConnectorException("SQL resource does not expose a physical TablePath");
            }
            return TablePath.of(resource.getName());
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("No connection in unit test");
        }
    }
}
