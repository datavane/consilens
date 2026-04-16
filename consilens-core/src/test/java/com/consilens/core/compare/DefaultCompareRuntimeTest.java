package com.consilens.core.compare;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.core.compare.plan.PushdownChecksumPlan;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class DefaultCompareRuntimeTest {

    @Test
    void shouldAttachNormalizationToReadOptionsInsteadOfConnection() throws Exception {
        CapturingConnectorRegistry registry = new CapturingConnectorRegistry(
                new StubConnectorAdapter(),
                new StubConnectorAdapter());
        DefaultCompareRuntime runtime = new DefaultCompareRuntime(
                registry,
                (request, source, target) -> new PushdownChecksumPlan(CompareExecutionSettings.fromRequest(request)),
                List.of(new StubPlanExecutor()));

        NormalizationSpec normalizationSpec = NormalizationSpec.builder().build();
        CompareRequest request = CompareRequest.builder()
                .source(connectorConfig("source-orders"))
                .target(connectorConfig("target-orders"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .normalizationSpec(normalizationSpec)
                .build();

        runtime.execute(request);

        ConnectorConfig capturedSource = registry.createdConfigs.removeFirst();
        ConnectorConfig capturedTarget = registry.createdConfigs.removeFirst();

        assertFalse(capturedSource.getConnection().containsKey("normalization"));
        assertSame(normalizationSpec, capturedSource.getReadOptions().getOptions().get("normalization"));
        assertEquals("source", capturedSource.getReadOptions().getOptions().get("normalizationSide"));

        assertFalse(capturedTarget.getConnection().containsKey("normalization"));
        assertSame(normalizationSpec, capturedTarget.getReadOptions().getOptions().get("normalization"));
        assertEquals("target", capturedTarget.getReadOptions().getOptions().get("normalizationSide"));
    }

    private ConnectorConfig connectorConfig(String tableName) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://localhost:3306/test");
        connection.put("username", "root");
        connection.put("password", "secret");
        return ConnectorConfig.builder()
                .type("mysql")
                .connection(connection)
                .resource(ResourceLocator.builder().type("table").name(tableName).build())
                .readOptions(ReadOptions.builder().options(new LinkedHashMap<>()).build())
                .build();
    }

    private static final class CapturingConnectorRegistry implements ConnectorRegistry {

        private final Deque<ConnectorAdapter> adapters;
        private final Deque<ConnectorConfig> createdConfigs = new ArrayDeque<>();

        private CapturingConnectorRegistry(ConnectorAdapter first, ConnectorAdapter second) {
            this.adapters = new ArrayDeque<>(List.of(first, second));
        }

        @Override
        public ConnectorAdapter create(ConnectorConfig config) {
            createdConfigs.addLast(config);
            return adapters.removeFirst();
        }

        @Override
        public Optional<com.consilens.connector.api.spi.ConnectorProvider> findProvider(String type) {
            return Optional.empty();
        }
    }

    private static final class StubConnectorAdapter implements ConnectorAdapter {

        @Override
        public String getType() {
            return "mysql";
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public DatasetHandle openDataset(ResourceLocator resource, ReadOptions readOptions) {
            return new StubDatasetHandle(resource);
        }

        @Override
        public void close() {
        }
    }

    private static final class StubDatasetHandle implements DatasetHandle {

        private final ResourceLocator resource;

        private StubDatasetHandle(ResourceLocator resource) {
            this.resource = resource;
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
            return SchemaDescriptor.builder().build();
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
    }

    private static final class StubPlanExecutor implements PlanExecutor<ComparePlan> {

        @Override
        public boolean supports(ComparePlan plan) {
            return true;
        }

        @Override
        public DiffResult execute(ComparePlan plan, CompareRequest request, CompareSegment source, CompareSegment target) {
            return mock(DiffResult.class);
        }
    }
}
