package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.TableMetadata;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.NoopDiffLifecycle;
import com.consilens.sdk.ConsilensEngine;
import com.consilens.sdk.DefaultConsilensEngine;
import com.consilens.sink.api.DefaultDiffLifecycle;
import com.consilens.sink.api.model.ResultConfig;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced service for performing data diff operations using core algorithms.
 */
@Slf4j
public class DiffService {

    private final CompareRequestFactory compareRequestFactory = new CompareRequestFactory();
    private final CompareRuntime runtimeOverride;

    public DiffService() {
        this(null);
    }

    /**
     * @param runtimeOverride optional engine override (for example DuckDB);
     *                        null uses {@link DefaultCompareRuntime}
     */
    public DiffService(CompareRuntime runtimeOverride) {
        this.runtimeOverride = runtimeOverride;
    }

    /**
     * Perform data diff operation based on configuration using core algorithms.
     *
     * @param config the diff configuration
     * @return diff result
     */
    public CliDiffResult performDiff(CliConfiguration config) throws Exception {
        return performDiff(config, null);
    }

    /**
     * @param window optional incremental window compiled into the request filters
     */
    public CliDiffResult performDiff(CliConfiguration config, WindowSpec window) throws Exception {
        log.info("Starting diff operation with configured strategy: {}, algorithm: {}",
                config.getStrategyMode(), config.getAlgorithm());

        long startTime = System.currentTimeMillis();

        DiffLifecycle lifecycle = buildLifecycle(config);
        DiffContext diffContext = buildDiffContext(config);
        CliDiffResult result = null;
        Exception failure = null;

        try {
            lifecycle.onDiffStart(diffContext);
            DiffResult coreResult;
            try (ConsilensEngine engine = createEngine()) {
                coreResult = engine.execute(toCompareRequest(config, window));
            }
            if (coreResult == null) {
                throw new IllegalStateException("compare runtime returned no result");
            }
            Object executedPlan = coreResult.getMetadata() == null ? null
                    : coreResult.getMetadata().get("executedPlan");
            if (executedPlan != null) {
                log.info("Executed compare plan: {}", executedPlan);
            }

            publishDifferences(coreResult, lifecycle, diffContext);
            publishWithinTolerance(coreResult, lifecycle, diffContext);
            lifecycle.onDiffComplete(coreResult, diffContext);

            // Convert core result to CLI result
            result = convertToCLIResult(coreResult, config.getStrategyMode(), config);
            if (result.getInfoTree() != null) {
                log.info(formatInfoTree(result.getInfoTree()));
            }

            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            result.initializeTimestamp();

            log.info("Diff operation completed in {} ms with {} differences", duration, result.getTotalDifferences());

        } catch (Exception e) {
            log.error("Diff operation failed", e);
            failure = new Exception("Diff operation failed: " + e.getMessage(), e);
            try {
                lifecycle.onDiffError(diffContext, e);
            } catch (Exception lifecycleEx) {
                failure.addSuppressed(lifecycleEx);
            }
        } finally {
            try {
                lifecycle.close();
            } catch (Exception closeEx) {
                if (failure != null) {
                    failure.addSuppressed(closeEx);
                } else {
                    failure = new Exception("Lifecycle close failed: " + closeEx.getMessage(), closeEx);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
        return result;
    }

    protected CompareRuntime createCompareRuntime() {
        return runtimeOverride != null ? runtimeOverride : new DefaultCompareRuntime();
    }

    /**
     * CLI uses the same SDK facade as platform integrators; the underlying
     * runtime remains the existing DefaultCompareRuntime.
     */
    protected ConsilensEngine createEngine() {
        return new DefaultConsilensEngine(createCompareRuntime());
    }

    protected DiffLifecycle buildLifecycle(CliConfiguration config) {
        ResultConfig resultConfig = config.getResult();
        if (resultConfig == null || resultConfig.getSinks() == null || resultConfig.getSinks().isEmpty()) {
            return new NoopDiffLifecycle();
        }
        return new DefaultDiffLifecycle(resultConfig);
    }

    protected DiffContext buildDiffContext(CliConfiguration config) {
        TablePath sourcePath = null;
        TablePath targetPath = null;
        List<String> sourceColumnNames = compareRequestFactory.sourceColumns(config);
        List<String> targetColumnNames = compareRequestFactory.targetColumns(config);

        if (isTableResource(config.getSource())) {
            sourcePath = resolveTablePath(config.getSource().getResource());
        }
        if (isTableResource(config.getTarget())) {
            targetPath = resolveTablePath(config.getTarget().getResource());
        }
        return DiffContext.builder()
                .sourceTablePath(sourcePath)
                .targetTablePath(targetPath)
                .strategy(config.getStrategyMode())
                .algorithm(config.getAlgorithm())
                .sourceColumnNames(sourceColumnNames)
                .targetColumnNames(targetColumnNames)
                .build();
    }

    private void publishDifferences(DiffResult coreResult, DiffLifecycle lifecycle, DiffContext diffContext) throws Exception {
        if (coreResult == null || coreResult.getDifferences() == null || coreResult.getDifferences().isEmpty()) {
            return;
        }
        lifecycle.onDifferencesFound(coreResult.getDifferences(), diffContext);
    }

    /**
     * 容差内行走单独事件：它们不是差异，混进 {@code onDifferencesFound} 会污染 sink 的
     * 行数统计、截断标记与"已写差异数"。
     */
    private void publishWithinTolerance(DiffResult coreResult, DiffLifecycle lifecycle, DiffContext diffContext)
            throws Exception {
        if (coreResult == null || coreResult.getWithinToleranceDifferences() == null
                || coreResult.getWithinToleranceDifferences().isEmpty()) {
            return;
        }
        lifecycle.onWithinToleranceFound(coreResult.getWithinToleranceDifferences(), diffContext);
    }

    /**
     * Convert core DiffResult to CLI DiffResult.
     */
    private CliDiffResult convertToCLIResult(DiffResult coreResult, String strategy, CliConfiguration config) {
        if (coreResult == null) {
            throw new IllegalStateException("compare runtime returned no result");
        }
        if (coreResult.getStatistics() == null) {
            // 没有统计不能当成 0 差异：那会把“没比/比失败”显示成完全一致。
            throw new IllegalStateException("compare result has no statistics");
        }

        DiffResult.DiffStatistics stats = coreResult.getStatistics();

        // Determine key columns for sorting based on source/target key configuration.
        List<String> sortKeyColumns = determineSortKeyColumns(config);

        // Create table metadata
        TableMetadata tableMetadata = createTableMetadata(config);

        return CliDiffResult.builder()
                .strategy(strategy)
                .sourceMissingCount(stats.getSourceMissingCount())
                .targetMissingCount(stats.getTargetMissingCount())
                .mismatchCount(stats.getMismatchCount())
                .totalDifferences(stats.getTotalDifferences())
                .sourceRowCount(stats.getSourceRowCount())
                .targetRowCount(stats.getTargetRowCount())
                .differences(convertDiffRows(coreResult.getDifferences()))
                .withinToleranceDifferences(convertDiffRows(coreResult.getWithinToleranceDifferences()))
                .withinToleranceCount(stats.getWithinToleranceCount())
                .tableMetadata(tableMetadata)
                .infoTree(coreResult.getInfoTree() != null && coreResult.getInfoTree().isPresent()
                        ? coreResult.getInfoTree().orElse(null)
                        : null)
                .build()
                .withSortKeyColumns(sortKeyColumns);
    }

    /**
     * Determine key columns for sorting based on source/target priority.
     */
    private List<String> determineSortKeyColumns(CliConfiguration config) {
        List<String> sourceKeyColumns = compareRequestFactory.logicalSortKeys(config);
        if (sourceKeyColumns != null && !sourceKeyColumns.isEmpty()) {
            log.debug("Using source key columns for sorting: {}", sourceKeyColumns);
            return sourceKeyColumns;
        } else {
            log.debug("No key columns found, will use first column for sorting");
            return new ArrayList<>();
        }
    }

    /**
     * Create table metadata from configuration.
     */
    private TableMetadata createTableMetadata(CliConfiguration config) {
        log.debug("Creating TableMetadata from config: sourceTable={}, targetTable={}",
                resourceDisplayName(config.getSource()), resourceDisplayName(config.getTarget()));

        List<String> sourceColumns = compareRequestFactory.sourceColumns(config);
        List<String> targetColumns = compareRequestFactory.targetColumns(config);

        TableMetadata metadata = TableMetadata.builder()
                .sourceTable(resourceDisplayName(config.getSource()))
                .targetTable(resourceDisplayName(config.getTarget()))
                .sourceColumns(sourceColumns)
                .targetColumns(targetColumns)
                .build();

        log.debug("Created TableMetadata: sourceTable={}, targetTable={}, sourceColumns={}, targetColumns={}",
                metadata.getSourceTable(), metadata.getTargetTable(), metadata.getSourceColumns(), metadata.getTargetColumns());

        return metadata;
    }

    /**
     * Convert diff rows from core to CLI model.
     */
    private List<DiffRow> convertDiffRows(List<DiffRow> coreDiffRows) {
        if (coreDiffRows == null) {
            return new ArrayList<>();
        }

        // For now, return the core diff rows directly
        // In the future, we might need to convert to CLI-specific diff row model
        return new ArrayList<>(coreDiffRows);
    }

    private CompareRequest toCompareRequest(CliConfiguration config, WindowSpec window) {
        return compareRequestFactory.create(config, window);
    }

    private boolean isTableResource(ConnectionConfig connectionConfig) {
        return connectionConfig != null
                && connectionConfig.getResource() != null
                && connectionConfig.getResource().getType() != null
                && "table".equalsIgnoreCase(connectionConfig.getResource().getType());
    }

    private TablePath resolveTablePath(ConnectionConfig.ResourceConfig resource) {
        return resource != null && resource.getName() != null ? TablePath.of(resource.getName()) : null;
    }

    private String resourceDisplayName(ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "";
        }
        ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        if ("table".equalsIgnoreCase(resource.getType())) {
            return resource.getName() != null ? resource.getName() : "";
        }
        return resource.getPath() != null ? resource.getPath() : "";
    }

    /**
     * Perform a dry run to validate configuration without executing diff.
     */
    public CliDiffResult performDryRun(CliConfiguration config) throws Exception {
        log.info("Performing dry run for diff operation with strategy: {}, algorithm: {}",
                config.getStrategyMode(), config.getAlgorithm());
        ConnectorProbeService probeService = new ConnectorProbeService();
        CompareRequest request = compareRequestFactory.create(config);

        try {
            long sourceRowCount = probeService.countRows(
                    request.getSource(),
                    request.getSourceKeySpec(),
                    request.getSourceComparisons(),
                    request.getSourceFilter(),
                    "source");
            long targetRowCount = probeService.countRows(
                    request.getTarget(),
                    request.getTargetKeySpec(),
                    request.getTargetComparisons(),
                    request.getTargetFilter(),
                    "target");

            log.info("Dry run completed - Source rows: {}, Target rows: {}", sourceRowCount, targetRowCount);

            TableMetadata tableMetadata = createTableMetadata(config);
            return CliDiffResult.builder()
                    .strategy(config.getStrategyMode())
                    .sourceMissingCount(0)
                    .targetMissingCount(0)
                    .mismatchCount(0)
                    .totalDifferences(0)
                    .sourceRowCount(sourceRowCount)
                    .targetRowCount(targetRowCount)
                    .differences(new java.util.ArrayList<>())
                    .tableMetadata(tableMetadata)
                    .build();
        } catch (Exception e) {
            log.error("Dry run failed", e);
            throw new Exception("Dry run failed: " + e.getMessage(), e);
        }
    }

    private String formatInfoTree(DiffResult.InfoTree infoTree) {
        StringBuilder builder = new StringBuilder();
        builder.append("InfoTree").append(System.lineSeparator());
        builder.append("Summary").append(System.lineSeparator());
        builder.append("  Nodes: ").append(infoTree.getTotalNodes())
                .append(", MaxDepth: ").append(infoTree.getMaxDepth())
                .append(System.lineSeparator());
        builder.append("  Segments: total=").append(infoTree.getTotalSegments())
                .append(", processed=").append(infoTree.getProcessedSegments())
                .append(", skipped=").append(infoTree.getSkippedSegments())
                .append(System.lineSeparator());
        builder.append("  Segments(LocalCompare/Bisect/Adaptive): ")
                .append(infoTree.getLocalCompareSegments()).append("/")
                .append(infoTree.getBisectSegments()).append("/")
                .append(infoTree.getAdaptiveSegments())
                .append(System.lineSeparator());
        builder.append("  Rows: scanned=").append(infoTree.getTotalRowsScanned())
                .append(", diffs=").append(infoTree.getTotalDifferences())
                .append(System.lineSeparator());
        if (infoTree.getStageTimeMs() != null && !infoTree.getStageTimeMs().isEmpty()) {
            builder.append("  StageTimeMs: ").append(infoTree.getStageTimeMs())
                    .append(System.lineSeparator());
        }
        if (infoTree.getMetrics() != null && !infoTree.getMetrics().isEmpty()) {
            builder.append("  Metrics: ").append(infoTree.getMetrics())
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        builder.append("Tree").append(System.lineSeparator());
        builder.append(formatInfoTreeAsTree(infoTree));
        return builder.toString();
    }

    private String formatInfoTreeAsTree(DiffResult.InfoTree infoTree) {
        if (infoTree.getNodes() == null || infoTree.getNodes().isEmpty()) {
            return "  <empty>" + System.lineSeparator();
        }

        Map<String, DiffResult.InfoTreeNode> nodeIndex = new HashMap<>();
        Map<String, List<DiffResult.InfoTreeNode>> children = new HashMap<>();
        for (DiffResult.InfoTreeNode node : infoTree.getNodes()) {
            nodeIndex.put(node.getNodeId(), node);
            String parent = node.getParentNodeId();
            children.computeIfAbsent(parent, key -> new ArrayList<>()).add(node);
        }

        String rootId = infoTree.getRootNodeId();
        DiffResult.InfoTreeNode root = nodeIndex.get(rootId);
        if (root == null) {
            root = children.values().stream()
                    .flatMap(List::stream)
                    .filter(node -> node.getParentNodeId() == null || "diff".equalsIgnoreCase(node.getParentNodeId()))
                    .findFirst()
                    .orElse(infoTree.getNodes().get(0));
        }

        StringBuilder builder = new StringBuilder();
        renderInfoTreeNode(builder, root, children, 0);
        return builder.toString();
    }

    private void renderInfoTreeNode(StringBuilder builder,
                                    DiffResult.InfoTreeNode node,
                                    Map<String, List<DiffResult.InfoTreeNode>> children,
                                    int indentLevel) {
        String indent = "  ".repeat(Math.max(0, indentLevel));
        if (indentLevel == 0) {
            builder.append(formatInfoTreeNodeLine(node)).append(System.lineSeparator());
        } else {
            builder.append(indent).append("- ").append(formatInfoTreeNodeLine(node)).append(System.lineSeparator());
        }

        List<DiffResult.InfoTreeNode> childList = children.get(node.getNodeId());
        if (childList == null || childList.isEmpty()) {
            return;
        }
        childList.sort((a, b) -> compareRange(a, b));
        for (DiffResult.InfoTreeNode child : childList) {
            renderInfoTreeNode(builder, child, children, indentLevel + 1);
        }
    }

    private String formatInfoTreeNodeLine(DiffResult.InfoTreeNode node) {
        String label = formatNodeLabel(node);
        String range = (node.getMinKey() != null || node.getMaxKey() != null)
                ? String.valueOf(node.getMinKey()) + " .. " + String.valueOf(node.getMaxKey())
                : "null .. null";
        String split = node.getSplitType() != null
                ? node.getSplitType() + ", factor=" + node.getSplitFactor() + ", times=" + node.getSplitTimes()
                : "none";
        return label
                + " | phase=" + node.getPhase()
                + " | depth=" + node.getDepth()
                + " | range=[" + range + "]"
                + " | counts=source:" + node.getSourceCount() + ", target:" + node.getTargetCount()
                + " | diffs=" + node.getDiffCount()
                + " | split=" + split
                + " | durationMs=" + node.getDurationMs()
                + formatNodeMetrics(node);
    }

    private String formatNodeMetrics(DiffResult.InfoTreeNode node) {
        if (node.getMetrics() == null || node.getMetrics().isEmpty()) {
            return "";
        }
        return " | metrics=" + node.getMetrics();
    }

    private String formatNodeLabel(DiffResult.InfoTreeNode node) {
        if (node.getName() != null && !node.getName().isBlank()) {
            if ("segment".equalsIgnoreCase(node.getPhase())) {
                return "segment";
            }
            return node.getName();
        }
        String nodeId = node.getNodeId();
        if (nodeId == null) {
            return "node";
        }
        if ("algorithm".equalsIgnoreCase(node.getPhase())) {
            return "L" + node.getDepth();
        }
        return shortenIdentifier(nodeId);
    }

    private String shortenIdentifier(String value) {
        if (value == null) {
            return "node";
        }
        String trimmed = value;
        int idx = trimmed.indexOf("TablePath(pathComponents=[");
        if (idx >= 0) {
            int start = idx + "TablePath(pathComponents=[".length();
            int end = trimmed.indexOf("]", start);
            if (end > start) {
                String table = trimmed.substring(start, end);
                return trimmed.substring(0, idx) + "table=" + table + trimmed.substring(end + 1);
            }
        }
        return trimmed;
    }

    private int compareRange(DiffResult.InfoTreeNode a, DiffResult.InfoTreeNode b) {
        String aMin = a.getMinKey() != null ? String.valueOf(a.getMinKey()) : null;
        String bMin = b.getMinKey() != null ? String.valueOf(b.getMinKey()) : null;
        int minCmp = compareNullable(aMin, bMin);
        if (minCmp != 0) {
            return minCmp;
        }
        String aMax = a.getMaxKey() != null ? String.valueOf(a.getMaxKey()) : null;
        String bMax = b.getMaxKey() != null ? String.valueOf(b.getMaxKey()) : null;
        int maxCmp = compareNullable(aMax, bMax);
        if (maxCmp != 0) {
            return maxCmp;
        }
        return String.valueOf(a.getNodeId()).compareTo(String.valueOf(b.getNodeId()));
    }

    private int compareNullable(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }
}
