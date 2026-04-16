package com.consilens.core.compare;

import com.consilens.connector.api.capability.CapabilitySet;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultComparePlannerTest {

    private final DefaultComparePlanner planner = new DefaultComparePlanner();

    @Test
    void shouldRespectPreferredJoinPlanWhenAvailable() {
        CompareRequest request = CompareRequest.builder()
                .strategyPreference(CompareStrategyPreference.builder()
                        .preferredPlans(java.util.List.of(ComparePlanTypes.SERVER_JOIN))
                        .allowFallback(false)
                        .build())
                .executionOptions(CompareExecutionOptions.builder()
                        .checksumAlgorithm("xor")
                        .bisectionFactor(8)
                        .build())
                .build();

        DatasetHandle source = dataset("shared", capabilities(
                ConnectorCapability.SERVER_SIDE_JOIN,
                ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = dataset("shared", capabilities(
                ConnectorCapability.SERVER_SIDE_JOIN,
                ConnectorCapability.SERVER_SIDE_HASH));

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.SERVER_JOIN, plan.getPlanType());
    }

    @Test
    void shouldFallbackToChecksumWhenPreferredJoinUnavailableAndFallbackAllowed() {
        CompareRequest request = CompareRequest.builder()
                .strategyPreference(CompareStrategyPreference.builder()
                        .preferredPlans(java.util.List.of(ComparePlanTypes.SERVER_JOIN))
                        .allowFallback(true)
                        .build())
                .build();

        DatasetHandle source = dataset("left", capabilities(ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = dataset("right", capabilities(ConnectorCapability.SERVER_SIDE_HASH));

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.PUSHDOWN_CHECKSUM, plan.getPlanType());
    }

    @Test
    void shouldFailWhenPreferredPlanUnavailableAndFallbackDisabled() {
        CompareRequest request = CompareRequest.builder()
                .strategyPreference(CompareStrategyPreference.builder()
                        .preferredPlans(java.util.List.of(ComparePlanTypes.SERVER_JOIN))
                        .allowFallback(false)
                        .build())
                .build();

        DatasetHandle source = dataset("left", capabilities(ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = dataset("right", capabilities(ConnectorCapability.SERVER_SIDE_HASH));

        assertThrows(IllegalStateException.class, () -> planner.plan(request, source, target));
    }

    @Test
    void shouldIgnoreAdvertisedKeyHashCapabilitiesWithoutProviders() {
        CompareRequest request = CompareRequest.builder()
                .build();

        DatasetHandle source = dataset("left", capabilities(
                ConnectorCapability.SERVER_SIDE_HASH,
                ConnectorCapability.KEY_LOOKUP,
                ConnectorCapability.STREAM_SCAN));
        DatasetHandle target = dataset("right", capabilities(
                ConnectorCapability.SERVER_SIDE_HASH,
                ConnectorCapability.KEY_LOOKUP,
                ConnectorCapability.STREAM_SCAN));

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.PUSHDOWN_CHECKSUM, plan.getPlanType());
    }

    private DatasetHandle dataset(String executionDomainId, CapabilitySet capabilities) {
        DatasetMetadata metadata = DatasetMetadata.builder()
                .logicalName("orders")
                .executionDomainId(executionDomainId)
                .capabilities(capabilities)
                .build();
        return new StubDatasetHandle(metadata);
    }

    private CapabilitySet capabilities(ConnectorCapability... capabilities) {
        return new CapabilitySet(EnumSet.copyOf(java.util.List.of(capabilities)));
    }

    private static final class StubDatasetHandle implements DatasetHandle {

        private final DatasetMetadata metadata;

        private StubDatasetHandle(DatasetMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public ResourceLocator getResource() {
            return ResourceLocator.builder().type("table").name("orders").build();
        }

        @Override
        public DatasetMetadata getMetadata() {
            return metadata;
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
}
