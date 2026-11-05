package com.consilens.cluster.runtime;

import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmitRequest;
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
import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.connector.api.spi.ConnectorProvider;
import com.consilens.core.compare.ComparePlan;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.compare.PlanExecutor;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClusterSubmitterIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldMatchDirectCompareRuntimeResultThroughPortableLocalSubmission() throws Exception {
        CompareRequest compareRequest = compareRequest(ExecutionMode.LOCAL);
        DefaultCompareRuntime compareRuntime = controlledCompareRuntime();

        DiffResult directResult = compareRuntime.execute(compareRequest);
        ClusterSubmitRequest submissionRequest = portableRequest(compareRequest);
        LocalClusterSubmitter submitter = new LocalClusterSubmitter(request -> LocalSimulationRequest.builder()
                .submissionId(request.getSubmissionId())
                .executionSpec(compareRequest.getClusterExecutionSpec())
                .splitTasks(List.of(compareTask(compareRequest, compareRuntime)))
                .manifestDirectory(temporaryDirectory)
                .build());

        com.consilens.cluster.api.ClusterSubmission submission = submitter.submit(submissionRequest);
        DiffResult simulatedResult = submitter.findResult("submission-1")
                .orElseThrow()
                .getDiffResult();

        assertEquals(1, submission.getSplitCount());
        assertEquals(directResult.getStatistics().getSourceMissingCount(),
                simulatedResult.getStatistics().getSourceMissingCount());
        assertEquals(directResult.getStatistics().getTargetMissingCount(),
                simulatedResult.getStatistics().getTargetMissingCount());
        assertEquals(directResult.getStatistics().getMismatchCount(),
                simulatedResult.getStatistics().getMismatchCount());
        assertEquals(directResult.getStatistics().getTotalDifferences(),
                simulatedResult.getStatistics().getTotalDifferences());
        assertEquals(diffKeysAndOperations(directResult), diffKeysAndOperations(simulatedResult));
        assertTrue(temporaryDirectory.resolve("manifest.json").toFile().isFile());
    }

    @Test
    void shouldRejectNonLocalEnvelopeBeforeResolvingInProcessTask() {
        CompareRequest compareRequest = compareRequest(ExecutionMode.YARN);
        AtomicBoolean resolved = new AtomicBoolean();
        LocalClusterSubmitter submitter = new LocalClusterSubmitter(request -> {
            resolved.set(true);
            throw new AssertionError("LOCAL task provider must not be called for YARN");
        });

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> submitter.submit(portableRequest(compareRequest)));

        assertTrue(exception.getMessage().contains("LOCAL"));
        assertFalse(resolved.get());
    }

    @Test
    void shouldRejectIncompleteEnvelopeBeforeResolvingInProcessTask() {
        AtomicBoolean resolved = new AtomicBoolean();
        LocalClusterSubmitter submitter = new LocalClusterSubmitter(request -> {
            resolved.set(true);
            throw new AssertionError("task provider must not be called for an invalid envelope");
        });

        List<ClusterSubmitRequest> invalidRequests = List.of(
                ClusterSubmitRequest.builder()
                        .comparison(comparisonDescription())
                        .execution(executionDescription())
                        .build(),
                ClusterSubmitRequest.builder()
                        .submissionId("submission-1")
                        .execution(executionDescription())
                        .build(),
                ClusterSubmitRequest.builder()
                        .submissionId("submission-1")
                        .comparison(comparisonDescription())
                        .build());

        for (ClusterSubmitRequest invalidRequest : invalidRequests) {
            assertThrows(IllegalArgumentException.class, () -> submitter.submit(invalidRequest));
        }
        assertFalse(resolved.get());
    }

    private LocalSplitTask compareTask(CompareRequest request, DefaultCompareRuntime compareRuntime) {
        return new LocalSplitTask() {
            @Override
            public String getSplitId() {
                return "local-compare";
            }

            @Override
            public DiffResult execute() throws Exception {
                return compareRuntime.execute(request);
            }
        };
    }

    private ClusterSubmitRequest portableRequest(CompareRequest compareRequest) {
        return ClusterSubmitRequest.from("submission-1", compareRequest,
                comparisonDescription());
    }

    private ClusterComparisonDescription comparisonDescription() {
        return ClusterComparisonDescription.builder()
                .sourceConfigRef("in-process/source")
                .targetConfigRef("in-process/target")
                .build();
    }

    private ClusterExecutionDescription executionDescription() {
        return ClusterExecutionDescription.builder().executionMode(ExecutionMode.LOCAL).build();
    }

    private CompareRequest compareRequest(ExecutionMode executionMode) {
        return CompareRequest.builder()
                .source(connectorConfig("source_orders"))
                .target(connectorConfig("target_orders"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .clusterExecutionSpec(ClusterExecutionSpec.builder()
                        .executionMode(executionMode)
                        .maxAttempts(1)
                        .build())
                .build();
    }

    private ConnectorConfig connectorConfig(String tableName) {
        return ConnectorConfig.builder()
                .type("controlled")
                .resource(ResourceLocator.builder().type("table").name(tableName).build())
                .readOptions(ReadOptions.builder().build())
                .build();
    }

    private DefaultCompareRuntime controlledCompareRuntime() {
        ConnectorRegistry registry = new ConnectorRegistry() {
            @Override
            public ConnectorAdapter create(ConnectorConfig config) {
                return new ControlledConnectorAdapter();
            }

            @Override
            public Optional<ConnectorProvider> findProvider(String type) {
                return Optional.empty();
            }
        };
        return new DefaultCompareRuntime(registry, (request, source, target) -> new ControlledPlan(),
                List.of(new ControlledPlanExecutor()));
    }

    private List<String> diffKeysAndOperations(DiffResult result) {
        return result.getDifferences().stream()
                .map(row -> row.getOperation().name() + ":" + row.getPrimaryKeyString())
                .collect(Collectors.toList());
    }

    private static final class ControlledPlan implements ComparePlan {

        @Override
        public String getPlanType() {
            return "controlled";
        }
    }

    private static final class ControlledPlanExecutor implements PlanExecutor<ControlledPlan> {

        @Override
        public boolean supports(ComparePlan plan) {
            return plan instanceof ControlledPlan;
        }

        @Override
        public DiffResult execute(ControlledPlan plan,
                                  CompareRequest request,
                                  com.consilens.connector.api.planner.CompareSegment source,
                                  com.consilens.connector.api.planner.CompareSegment target) {
            return DiffResult.of(List.of(
                    DiffRow.modified(List.of(7L), List.of("source-value"), List.of("target-value"),
                            List.of("value"), List.of("value"))),
                    com.consilens.connector.api.model.TablePath.of(source.getResource().getName()),
                    com.consilens.connector.api.model.TablePath.of(target.getResource().getName()));
        }
    }

    private static final class ControlledConnectorAdapter implements ConnectorAdapter {

        @Override
        public String getType() {
            return "controlled";
        }

        @Override
        public String getName() {
            return "controlled";
        }

        @Override
        public DatasetHandle openDataset(ResourceLocator resource, ReadOptions readOptions) {
            return new ControlledDatasetHandle(resource);
        }

        @Override
        public void close() {
        }
    }

    private static final class ControlledDatasetHandle implements DatasetHandle {

        private final ResourceLocator resource;

        private ControlledDatasetHandle(ResourceLocator resource) {
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
            return SchemaDescriptor.builder().fields(List.of()).fieldMap(Map.of()).build();
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
