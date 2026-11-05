package com.consilens.core.compare;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.normalization.DefaultNormalizationSpecValidator;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;
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

            validatePartitionRequirement("source", request.getSourceFilter(), sourceDataset);
            validatePartitionRequirement("target", request.getTargetFilter(), targetDataset);

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
            DiffResult result = executor.execute(plan, request, sourceSegment, targetSegment);
            if (result != null) {
                // 记录实际执行的计划：CLI 日志按配置打印 strategy，但 core 可能因方言能力
                // 降级（如 Oracle 走 streaming），把真实计划带回结果，避免日志互相矛盾。
                result.getMetadata().put("executedPlan", String.valueOf(plan.getPlanType()));
            }
            return result;
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
        // consistency=snapshot 必须真的拿到快照，否则静默退化为普通读 → 结果被并发写污染。
        String consistency = config.getReadOptions() != null ? config.getReadOptions().getConsistency() : null;
        requireSnapshotProvider(side, consistency, dataset.getSnapshotProvider().isPresent());
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

    /** P2-1：请求了 snapshot 但数据源没有快照能力时必须快速失败，不能静默降级为普通读。 */
    static void requireSnapshotProvider(String side, String consistency, boolean hasSnapshotProvider) {
        boolean snapshotRequested = consistency != null && "snapshot".equalsIgnoreCase(consistency.trim());
        if (snapshotRequested && !hasSnapshotProvider) {
            throw new ConnectorException("consistency=snapshot was requested for " + side
                    + " but this connector/datasource does not provide snapshots; "
                    + "remove the setting or use consistency=none");
        }
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
            warnOnPrecisionContract(request.getNormalizationSpec());
        }
    }

    /**
     * format_number 的 precision 只会作用在 SQL 归一化表达式上；本地逐行比较仍使用
     * ValueNormalizer 的固定 4 位小数。precision 低于 4 时，checksum 段校验比本地比较更粗，
     * 第 precision 位之后的真实差异可能在"校验和相等"这一步就被跳过（漏报）。
     * 这里显式告警；真正的统一需要把精度契约同时接入 Java 归一化路径。
     */
    private void warnOnPrecisionContract(NormalizationSpec spec) {
        Integer coarsePrecision = coarseDecimalPrecision(spec);
        if (coarsePrecision != null) {
            log.warn("normalization precision {} is below the local comparison precision (4): "
                            + "checksum screening uses the configured (coarser) precision, so "
                            + "differences beyond it can be missed. Keep precision >= 4 until both "
                            + "the SQL and Java normalizers share the same contract.", coarsePrecision);
        }
    }

    /** @return 配置里最小的 format_number precision（&lt; 4 时返回），用于契约告警；无配置返回 null。 */
    static Integer coarseDecimalPrecision(NormalizationSpec spec) {
        if (spec == null) {
            return null;
        }
        Integer min = null;
        for (List<NormalizationRule> rules : new List[] {spec.getGlobal(), spec.getSource(), spec.getTarget()}) {
            if (rules == null) {
                continue;
            }
            for (NormalizationRule rule : rules) {
                if (rule == null || !"format_number".equals(rule.getOperation()) || rule.getParams() == null) {
                    continue;
                }
                Object precision = rule.getParams().get("precision");
                if (precision instanceof Number && ((Number) precision).intValue() < 4) {
                    int value = ((Number) precision).intValue();
                    min = min == null ? value : Math.min(min, value);
                }
            }
        }
        return min;
    }

    private void validatePartitionRequirement(String side,
                                              PredicateSpec filter,
                                              DatasetHandle dataset) {
        if (dataset == null || dataset.getMetadata() == null || dataset.getMetadata().getAttributes() == null) {
            return;
        }
        DatabaseDialect dialect = dataset.getSupport(RelationalDatasetSupport.class)
                .map(RelationalDatasetSupport::getDialect)
                .orElse(null);
        if (dialect == null || !dialect.discoversPartitionAttributes()) {
            return;
        }
        Map<String, Object> attributes = dataset.getMetadata().getAttributes();
        boolean partitioned = Boolean.TRUE.equals(attributes.get("partitioned"));
        if (!partitioned) {
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
            throw new ConnectorException(dialect.getConnectorType() + " " + side
                    + " table is partitioned but no partition predicate can be resolved. "
                    + "Please include partition keys in comparison.filters." + side + ".");
        }
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

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            // 单个资源关闭失败不能中断后续关闭，也不能把成功比对变成失败
            log.warn("Failed to close {}: {}", closeable.getClass().getSimpleName(),
                    exception.getMessage());
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
