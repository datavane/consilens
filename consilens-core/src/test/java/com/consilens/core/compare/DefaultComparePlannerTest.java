package com.consilens.core.compare;

import com.consilens.connector.api.capability.CapabilitySet;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.sql.Connection;
import java.sql.SQLException;

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

        DatasetHandle source = relationalDataset("shared", capabilities(
                ConnectorCapability.SERVER_SIDE_JOIN,
                ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = relationalDataset("shared", capabilities(
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

        DatasetHandle source = relationalDataset("left", capabilities(ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = relationalDataset("right", capabilities(ConnectorCapability.SERVER_SIDE_HASH));

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

        DatasetHandle source = relationalDataset("left", capabilities(ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = relationalDataset("right", capabilities(ConnectorCapability.SERVER_SIDE_HASH));

        assertThrows(IllegalStateException.class, () -> planner.plan(request, source, target));
    }

    @Test
    void shouldIgnoreAdvertisedKeyHashCapabilitiesWithoutProviders() {
        CompareRequest request = CompareRequest.builder()
                .build();

        DatasetHandle source = dataset("left", capabilities(
                ConnectorCapability.SERVER_SIDE_HASH,
                ConnectorCapability.KEY_LOOKUP,
                ConnectorCapability.STREAM_SCAN), true);
        DatasetHandle target = dataset("right", capabilities(
                ConnectorCapability.SERVER_SIDE_HASH,
                ConnectorCapability.KEY_LOOKUP,
                ConnectorCapability.STREAM_SCAN), true);

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.STREAMING_MERGE, plan.getPlanType());
    }

    @Test
    void shouldSelectChecksumForRelationalDatasetsWithHashCapability() {
        CompareRequest request = CompareRequest.builder().build();

        DatasetHandle source = relationalDataset("left", capabilities(ConnectorCapability.SERVER_SIDE_HASH));
        DatasetHandle target = relationalDataset("right", capabilities(ConnectorCapability.SERVER_SIDE_HASH));

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.PUSHDOWN_CHECKSUM, plan.getPlanType());
    }

    @Test
    void shouldPreferChecksumForSqlResources() {
        CompareRequest request = CompareRequest.builder().build();

        DatasetHandle source = relationalDataset("shared", capabilities(
                ConnectorCapability.SERVER_SIDE_JOIN,
                ConnectorCapability.SERVER_SIDE_HASH), true, Map.of("resourceType", "sql"));
        DatasetHandle target = relationalDataset("shared", capabilities(
                ConnectorCapability.SERVER_SIDE_JOIN,
                ConnectorCapability.SERVER_SIDE_HASH), true, Map.of("resourceType", "table"));

        ComparePlan plan = planner.plan(request, source, target);

        assertEquals(ComparePlanTypes.PUSHDOWN_CHECKSUM, plan.getPlanType());
    }

    private DatasetHandle dataset(String executionDomainId, CapabilitySet capabilities) {
        return dataset(executionDomainId, capabilities, false);
    }

    private DatasetHandle relationalDataset(String executionDomainId, CapabilitySet capabilities) {
        return relationalDataset(executionDomainId, capabilities, false, Map.of());
    }

    private DatasetHandle dataset(String executionDomainId, CapabilitySet capabilities, boolean withScanner) {
        return dataset(executionDomainId, capabilities, withScanner, Map.of());
    }

    private DatasetHandle dataset(String executionDomainId,
                                  CapabilitySet capabilities,
                                  boolean withScanner,
                                  Map<String, Object> attributes) {
        DatasetMetadata metadata = DatasetMetadata.builder()
                .logicalName("orders")
                .executionDomainId(executionDomainId)
                .capabilities(capabilities)
                .attributes(attributes)
                .build();
        return new StubDatasetHandle(metadata, withScanner);
    }

    private DatasetHandle relationalDataset(String executionDomainId,
                                            CapabilitySet capabilities,
                                            boolean withScanner,
                                            Map<String, Object> attributes) {
        DatasetMetadata metadata = DatasetMetadata.builder()
                .logicalName("orders")
                .executionDomainId(executionDomainId)
                .capabilities(capabilities)
                .attributes(attributes)
                .build();
        return new StubRelationalDatasetHandle(metadata, withScanner);
    }

    private CapabilitySet capabilities(ConnectorCapability... capabilities) {
        return new CapabilitySet(EnumSet.copyOf(java.util.List.of(capabilities)));
    }

    private static class StubDatasetHandle implements DatasetHandle {

        private final DatasetMetadata metadata;
        private final boolean withScanner;

        private StubDatasetHandle(DatasetMetadata metadata, boolean withScanner) {
            this.metadata = metadata;
            this.withScanner = withScanner;
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
            if (!withScanner) {
                return Optional.empty();
            }
            return Optional.of(segment -> new com.consilens.connector.api.record.CloseableIterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public com.consilens.connector.api.record.CanonicalRecord next() {
                    throw new java.util.NoSuchElementException();
                }

                @Override
                public void close() {
                }
            });
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

    private static final class StubRelationalDatasetHandle extends StubDatasetHandle implements RelationalDatasetSupport {

        private StubRelationalDatasetHandle(DatasetMetadata metadata, boolean withScanner) {
            super(metadata, withScanner);
        }

        @Override
        public String getName() {
            return "orders";
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
            return null;
        }

        @Override
        public TablePath getTablePath() {
            return TablePath.of("orders");
        }

        @Override
        public Connection getConnection() throws SQLException {
            return null;
        }
    }
}
