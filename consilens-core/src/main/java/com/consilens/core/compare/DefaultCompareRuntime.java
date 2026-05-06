package com.consilens.core.compare;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.normalization.DefaultNormalizationSpecValidator;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.core.compare.executor.ChecksumPlanExecutor;
import com.consilens.core.compare.executor.JoinPlanExecutor;
import com.consilens.core.compare.executor.StreamingMergePlanExecutor;
import com.consilens.core.compare.registry.DefaultConnectorRegistry;
import com.consilens.core.diff.DiffResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class DefaultCompareRuntime implements CompareRuntime {

    private final ConnectorRegistry connectorRegistry;
    private final ComparePlanner comparePlanner;
    private final List<PlanExecutor<?>> executors;

    public DefaultCompareRuntime() {
        this(new DefaultConnectorRegistry(), new DefaultComparePlanner(), defaultExecutors());
    }

    public DefaultCompareRuntime(ConnectorRegistry connectorRegistry,
                                 ComparePlanner comparePlanner,
                                 List<PlanExecutor<?>> executors) {
        this.connectorRegistry = Objects.requireNonNull(connectorRegistry, "connectorRegistry");
        this.comparePlanner = Objects.requireNonNull(comparePlanner, "comparePlanner");
        this.executors = List.copyOf(Objects.requireNonNull(executors, "executors"));
    }

    @Override
    public DiffResult execute(CompareRequest request) throws Exception {
        validateRequest(request);

        ConnectorAdapter sourceConnector = null;
        ConnectorAdapter targetConnector = null;
        DatasetHandle sourceDataset = null;
        DatasetHandle targetDataset = null;

        try {
            ConnectorConfig sourceConfig = withNormalization(request.getSource(), request, "source");
            ConnectorConfig targetConfig = withNormalization(request.getTarget(), request, "target");

            sourceConnector = connectorRegistry.create(sourceConfig);
            targetConnector = connectorRegistry.create(targetConfig);

            sourceDataset = sourceConnector.openDataset(sourceConfig.getResource(), sourceConfig.getReadOptions());
            targetDataset = targetConnector.openDataset(targetConfig.getResource(), targetConfig.getReadOptions());

            validateDorisPartitionRequirement("source", request.getSourceFilter(), sourceDataset);
            validateDorisPartitionRequirement("target", request.getTargetFilter(), targetDataset);

            CompareSegment sourceSegment = buildSegment(
                    sourceDataset,
                    request.getSource(),
                    request.getSourceKeySpec(),
                    request.getSourceComparisons(),
                    request.getSourceFilter(),
                    "source");
            CompareSegment targetSegment = buildSegment(
                    targetDataset,
                    request.getTarget(),
                    request.getTargetKeySpec(),
                    request.getTargetComparisons(),
                    request.getTargetFilter(),
                    "target");

            ComparePlan plan = comparePlanner.plan(request, sourceDataset, targetDataset);
            log.info("Selected compare plan: {}", plan.getPlanType());
            PlanExecutor<ComparePlan> executor = resolveExecutor(plan);
            return executor.execute(plan, request, sourceSegment, targetSegment);
        } finally {
            closeQuietly(sourceDataset);
            closeQuietly(targetDataset);
            closeQuietly(sourceConnector);
            closeQuietly(targetConnector);
        }
    }

    @SuppressWarnings("unchecked")
    private PlanExecutor<ComparePlan> resolveExecutor(ComparePlan plan) {
        for (PlanExecutor<?> executor : executors) {
            if (executor.supports(plan)) {
                return (PlanExecutor<ComparePlan>) executor;
            }
        }
        throw new ConnectorException("No plan executor found for plan type: " + plan.getPlanType());
    }

    private CompareSegment buildSegment(DatasetHandle dataset,
                                        ConnectorConfig config,
                                        KeySpec keySpec,
                                        ComparisonSpec comparisons,
                                        PredicateSpec filter,
                                        String side) {
        SchemaDescriptor schema = dataset.getSchema();
        return CompareSegment.builder()
                .dataset(dataset)
                .resource(config.getResource())
                .keySpec(keySpec)
                .comparisons(comparisons)
                .filter(filter)
                .schema(schema)
                .side(side)
                .snapshot(dataset.getSnapshotProvider()
                        .map(provider -> provider.createSnapshot(config.getReadOptions()))
                        .orElse(null))
                .build();
    }

    private void validateRequest(CompareRequest request) {
        if (request == null) {
            throw new ConnectorException("CompareRequest cannot be null");
        }
        if (request.getSource() == null || request.getTarget() == null) {
            throw new ConnectorException("CompareRequest must define source and target connector");
        }
        if (request.getSource().getResource() == null || request.getTarget().getResource() == null) {
            throw new ConnectorException("Source and target connector must define resource");
        }
        if (request.getSourceKeySpec() == null || request.getTargetKeySpec() == null) {
            throw new ConnectorException("Source and target keySpec are required");
        }
        if (request.getSourceKeySpec().getFields() == null || request.getSourceKeySpec().getFields().isEmpty()) {
            throw new ConnectorException("Source keySpec.fields cannot be empty");
        }
        if (request.getTargetKeySpec().getFields() == null || request.getTargetKeySpec().getFields().isEmpty()) {
            throw new ConnectorException("Target keySpec.fields cannot be empty");
        }
        if (request.getNormalizationSpec() != null) {
            new DefaultNormalizationSpecValidator().validate(request.getNormalizationSpec());
        }
    }

    private void validateDorisPartitionRequirement(String side,
                                                   PredicateSpec filter,
                                                   DatasetHandle dataset) {
        if (dataset == null || dataset.getMetadata() == null || dataset.getMetadata().getAttributes() == null) {
            return;
        }
        Map<String, Object> attributes = dataset.getMetadata().getAttributes();
        String databaseType = stringValue(attributes.get("databaseType"));
        boolean partitioned = Boolean.TRUE.equals(attributes.get("partitioned"));
        if (!"doris".equalsIgnoreCase(databaseType) || !partitioned) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> partitionKeys = attributes.get("partitionKeys") instanceof List
                ? (List<String>) attributes.get("partitionKeys")
                : List.of();
        boolean resolved = false;
        if (filter != null && filter.getExpression() != null && !partitionKeys.isEmpty()) {
            String normalizedFilter = filter.getExpression().toLowerCase(java.util.Locale.ROOT);
            resolved = partitionKeys.stream()
                    .allMatch(key -> normalizedFilter.contains(key.toLowerCase(java.util.Locale.ROOT)));
        }
        if (!resolved) {
            throw new ConnectorException("Doris " + side
                    + " table is partitioned but no partition predicate can be resolved. "
                    + "Please include partition keys in comparison.filters." + side + ".");
        }
    }

    private String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private ConnectorConfig withNormalization(ConnectorConfig config, CompareRequest request, String side) {
        ReadOptions originalReadOptions = config.getReadOptions();
        Map<String, Object> options = originalReadOptions != null && originalReadOptions.getOptions() != null
                ? new LinkedHashMap<>(originalReadOptions.getOptions())
                : new LinkedHashMap<>();
        if (request.getNormalizationSpec() != null) {
            options.put("normalization", request.getNormalizationSpec());
            options.put("normalizationSide", side);
        }
        ReadOptions readOptions = ReadOptions.builder()
                .consistency(originalReadOptions != null ? originalReadOptions.getConsistency() : null)
                .batchSize(originalReadOptions != null ? originalReadOptions.getBatchSize() : null)
                .fetchSize(originalReadOptions != null ? originalReadOptions.getFetchSize() : null)
                .options(options.isEmpty() ? null : options)
                .build();
        return ConnectorConfig.builder()
                .type(config.getType())
                .name(config.getName())
                .connection(config.getConnection() != null ? new LinkedHashMap<>(config.getConnection()) : null)
                .resource(config.getResource())
                .readOptions(readOptions)
                .build();
    }

    private void closeQuietly(AutoCloseable closeable) throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }

    private static List<PlanExecutor<?>> defaultExecutors() {
        List<PlanExecutor<?>> result = new ArrayList<>();
        result.add(new JoinPlanExecutor());
        result.add(new ChecksumPlanExecutor());
        result.add(new StreamingMergePlanExecutor());
        return result;
    }
}
