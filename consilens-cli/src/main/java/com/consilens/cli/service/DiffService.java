package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.model.TableMetadata;
import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.algorithm.JoinDiffer;
import com.consilens.core.algorithm.TableDiffer;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffSink;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.NoopDiffLifecycle;
import com.consilens.core.segment.TableSegment;
import com.consilens.common.enums.ComparisonStrategy;
import com.consilens.sink.api.DefaultDiffLifecycle;
import com.consilens.sink.api.LifecycleDiffSink;
import com.consilens.sink.api.model.ResultConfig;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced service for performing data diff operations using core algorithms.
 */
@Slf4j
public class DiffService {

    /**
     * Perform data diff operation based on configuration using core algorithms.
     *
     * @param config the diff configuration
     * @return diff result
     */
    public CliDiffResult performDiff(CliConfiguration config) throws Exception {
        log.info("Starting diff operation with strategy: {}, algorithm: {}", 
                config.getStrategyMode(), config.getAlgorithm());

        long startTime = System.currentTimeMillis();

        DiffLifecycle lifecycle = buildLifecycle(config);
        DiffContext diffContext = buildDiffContext(config);

        try {
            lifecycle.onDiffStart(diffContext);

            // Create database adapters
            DatabaseAdapter sourceAdapter = DatabaseAdapterFactory.createSourceAdapter(config);
            DatabaseAdapter targetAdapter = DatabaseAdapterFactory.createTargetAdapter(config);

            // Create table segments
            TableSegment sourceSegment = TableSegmentFactory.createSourceTableSegment(config, sourceAdapter);
            TableSegment targetSegment = TableSegmentFactory.createTargetTableSegment(config, targetAdapter);

            // Validate configurations
            TableSegmentFactory.validateTableSegmentConfiguration(sourceSegment, config.getStrategyMode());
            TableSegmentFactory.validateTableSegmentConfiguration(targetSegment, config.getStrategyMode());

            // Create differ configuration
            TableDiffer.DifferConfig differConfig = createDifferConfig(config);

            // Create appropriate differ based on strategy
            TableDiffer differ = createDiffer(config.getStrategyMode(), differConfig);

            DiffSink diffSink = null;

            // Use result.sinks framework: bridge via LifecycleDiffSink for streaming
            if (lifecycle instanceof DefaultDiffLifecycle) {
                LifecycleDiffSink lifecycleBridge = new LifecycleDiffSink(lifecycle, diffContext);
                differ.setDiffSink(lifecycleBridge);
                diffSink = lifecycleBridge;
            }

            // Perform the diff operation
            CompletableFuture<DiffResult> future = differ.diffTables(sourceSegment, targetSegment);
            DiffResult coreResult;
            try {
                coreResult = future.get();
            } finally {
                if (diffSink != null) {
                    diffSink.close();
                }
            }

            lifecycle.onDiffComplete(coreResult, diffContext);

            // Convert core result to CLI result
            CliDiffResult result = convertToCLIResult(coreResult, config.getStrategyMode(), config);
            if (result.getInfoTree() != null) {
                log.info(formatInfoTree(result.getInfoTree()));
            }

            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);
            result.initializeTimestamp();

            log.info("Diff operation completed in {} ms with {} differences", duration, result.getTotalDifferences());

            // Clean up resources
            cleanupResources(sourceAdapter, targetAdapter, differ);

            return result;

        } catch (Exception e) {
            log.error("Diff operation failed", e);
            try {
                lifecycle.onDiffError(diffContext, e);
            } catch (Exception lifecycleEx) {
                log.warn("Lifecycle onDiffError failed", lifecycleEx);
            }
            throw new Exception("Diff operation failed: " + e.getMessage(), e);
        } finally {
            try {
                lifecycle.close();
            } catch (Exception e) {
                log.warn("Lifecycle close failed", e);
            }
        }
    }

    private DiffLifecycle buildLifecycle(CliConfiguration config) {
        ResultConfig resultConfig = config.getResult();
        if (resultConfig == null || resultConfig.getSinks() == null || resultConfig.getSinks().isEmpty()) {
            return new NoopDiffLifecycle();
        }
        return new DefaultDiffLifecycle(resultConfig);
    }

    private DiffContext buildDiffContext(CliConfiguration config) {
        TablePath sourcePath = null;
        TablePath targetPath = null;
        List<String> sourceColumnNames = new ArrayList<>();
        List<String> targetColumnNames = new ArrayList<>();

        if (config.getComparison() != null) {
            if (config.getComparison().getTables() != null) {
                String sourceTable = config.getComparison().getTables().getSource();
                String targetTable = config.getComparison().getTables().getTarget();
                if (sourceTable != null) sourcePath = TablePath.of(sourceTable);
                if (targetTable != null) targetPath = TablePath.of(targetTable);
            }
            // Merge key columns + compare columns to form the full column list for DDL
            if (config.getComparison().getKeys() != null) {
                List<String> srcKeys = config.getComparison().getKeys().getSource();
                List<String> tgtKeys = config.getComparison().getKeys().getTarget();
                if (srcKeys != null) sourceColumnNames.addAll(srcKeys);
                if (tgtKeys != null) targetColumnNames.addAll(tgtKeys);
            }
            if (config.getComparison().getCompareColumns() != null) {
                List<String> srcCols = config.getComparison().getCompareColumns().getSource();
                List<String> tgtCols = config.getComparison().getCompareColumns().getTarget();
                if (srcCols != null) {
                    for (String c : srcCols) {
                        if (!sourceColumnNames.contains(c)) sourceColumnNames.add(c);
                    }
                }
                if (tgtCols != null) {
                    for (String c : tgtCols) {
                        if (!targetColumnNames.contains(c)) targetColumnNames.add(c);
                    }
                }
            }
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

    /**
     * Create differ configuration from CLI configuration.
     */
    private TableDiffer.DifferConfig createDifferConfig(CliConfiguration config) {
        int bisectionFactor;
        if (config.getStrategy().getBisectionFactor() != null) {
            bisectionFactor = config.getStrategy().getBisectionFactor();
            log.debug("Using explicit bisectionFactor from config: {}", bisectionFactor);
        } else {
            bisectionFactor = 4;
            log.debug("No bisectionFactor provided, using default: {}", bisectionFactor);
        }
        
        // Use explicit bisectionThreshold if provided, otherwise calculate from batchSize
        long bisectionThreshold;
        if (config.getStrategy().getBisectionThreshold() != null) {
            bisectionThreshold = config.getStrategy().getBisectionThreshold();
        } else {
            bisectionThreshold = config.getStrategy().getBatchSize() != null ? config.getStrategy().getBatchSize() * 10 : 10000;
        }
        
        boolean enableProfiling = config.getStrategy().getEnableProfiling() != null ? config.getStrategy().getEnableProfiling() : false;

        ChecksumAlgorithm checksumAlgorithm = config.getAlgorithmEnum();
        log.info("Creating DifferConfig: bisectionFactor={}, bisectionThreshold={}, " +
                "enableProfiling={}, checksumAlgorithm={}",
                bisectionFactor, bisectionThreshold, enableProfiling,
                checksumAlgorithm);

        return new TableDiffer.DifferConfig(bisectionFactor, bisectionThreshold,
                                           enableProfiling, checksumAlgorithm,
                                           config.getConcurrency());
    }

    /**
     * Create appropriate differ based on algorithm type.
     */
    private TableDiffer createDiffer(String strategy, TableDiffer.DifferConfig config) {
        ComparisonStrategy strategyEnum = ComparisonStrategy.fromString(strategy);
        
        switch (strategyEnum) {
            case CHECKSUM:
                log.info("Creating ChecksumDiffer with config: bisectionFactor={}, threshold={}",
                        config.getBisectionFactor(), config.getBisectionThreshold());
                return new ChecksumDiffer(config);

            case JOIN:
                log.info("Creating JoinDiffer");
                // Create default join options
                JoinDiffer.JoinDifferOptions joinOptions = new JoinDiffer.JoinDifferOptions(
                        false // validateUniqueKeys
                );
                return new JoinDiffer(config, joinOptions);

            default:
                throw new IllegalArgumentException("Unsupported strategy: " + strategy);
        }
    }

    /**
     * Convert core DiffResult to CLI DiffResult.
     */
    private CliDiffResult convertToCLIResult(DiffResult coreResult, String strategy, CliConfiguration config) {
        if (coreResult == null || coreResult.getStatistics() == null) {
            return createEmptyResult(strategy);
        }

        DiffResult.DiffStatistics stats = coreResult.getStatistics();

        // Determine key columns for sorting based on source/target key configuration.
        List<String> sortKeyColumns = determineSortKeyColumns(config);

        // Create table metadata
        TableMetadata tableMetadata = createTableMetadata(config);

        return CliDiffResult.builder()
                .strategy(strategy)
                .sourceMissingCount((int) stats.getSourceMissingCount())
                .targetMissingCount((int) stats.getTargetMissingCount())
                .mismatchCount((int) stats.getMismatchCount())
                .totalDifferences((int) stats.getTotalDifferences())
                .sourceRowCount((int) stats.getSourceRowCount())
                .targetRowCount((int) stats.getTargetRowCount())
                .differences(convertDiffRows(coreResult.getDifferences()))
                .tableMetadata(tableMetadata)
                .infoTree(coreResult.getInfoTree().isPresent() ? coreResult.getInfoTree().orElse(null) : null)
                .build()
                .withSortKeyColumns(sortKeyColumns);
    }

    /**
     * Determine key columns for sorting based on source/target priority.
     */
    private List<String> determineSortKeyColumns(CliConfiguration config) {
        List<String> sourceKeyColumns = config.getComparison().getKeys().getSource();
        List<String> targetKeyColumns = config.getComparison().getKeys().getTarget();

        if (sourceKeyColumns != null && !sourceKeyColumns.isEmpty()) {
            log.debug("Using source key columns for sorting: {}", sourceKeyColumns);
            return sourceKeyColumns;
        } else if (targetKeyColumns != null && !targetKeyColumns.isEmpty()) {
            log.debug("Using target key columns for sorting: {}", targetKeyColumns);
            return targetKeyColumns;
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
                config.getComparison().getTables().getSource(), config.getComparison().getTables().getTarget());

        // Build column lists for each table
        List<String> sourceColumns = new ArrayList<>();
        List<String> targetColumns = new ArrayList<>();

        // Add key columns
        if (config.getComparison().getKeys().getSource() != null) {
            sourceColumns.addAll(config.getComparison().getKeys().getSource());
            log.debug("Added source keyColumns: {}", config.getComparison().getKeys().getSource());
        }
        if (config.getComparison().getKeys().getTarget() != null) {
            targetColumns.addAll(config.getComparison().getKeys().getTarget());
            log.debug("Added target keyColumns: {}", config.getComparison().getKeys().getTarget());
        }

        // Add comparison columns
        if (config.getComparison().getCompareColumns() != null
                && config.getComparison().getCompareColumns().getSource() != null) {
            sourceColumns.addAll(config.getComparison().getCompareColumns().getSource());
            log.debug("Added source compareColumns: {}", config.getComparison().getCompareColumns().getSource());
        }
        if (config.getComparison().getCompareColumns() != null
                && config.getComparison().getCompareColumns().getTarget() != null) {
            targetColumns.addAll(config.getComparison().getCompareColumns().getTarget());
            log.debug("Added target compareColumns: {}", config.getComparison().getCompareColumns().getTarget());
        }

        // Add extra columns
        if (config.getComparison().getExtraColumns() != null) {
            sourceColumns.addAll(config.getComparison().getExtraColumns());
            targetColumns.addAll(config.getComparison().getExtraColumns());
            log.debug("Added extraColumns: {}", config.getComparison().getExtraColumns());
        }

        TableMetadata metadata = TableMetadata.builder()
                .sourceTable(config.getComparison().getTables().getSource())
                .targetTable(config.getComparison().getTables().getTarget())
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

    /**
     * Create an empty diff result for cases where diff operation fails or returns
     * null.
     */
    private CliDiffResult createEmptyResult(String strategy) {
        // Create empty metadata for empty result
        TableMetadata emptyMetadata = TableMetadata.builder()
                .sourceTable("")
                .targetTable("")
                .sourceColumns(new ArrayList<>())
                .targetColumns(new ArrayList<>())
                .build();

        return CliDiffResult.builder()
                .strategy(strategy)
                .sourceMissingCount(0)
                .targetMissingCount(0)
                .mismatchCount(0)
                .totalDifferences(0)
                .sourceRowCount(0)
                .targetRowCount(0)
                .differences(new java.util.ArrayList<>())
                .tableMetadata(emptyMetadata)
                .build();
    }

    /**
     * Clean up resources after diff operation.
     */
    private void cleanupResources(DatabaseAdapter sourceAdapter, DatabaseAdapter targetAdapter, TableDiffer differ) {
        try {
            log.info(" Starting resource cleanup...");

            // Close database connection pools
            if (sourceAdapter != null && sourceAdapter.getConnectionPool() != null) {
                log.info("Closing source database connection pool...");
                sourceAdapter.getConnectionPool().close();
            }
            if (targetAdapter != null && targetAdapter.getConnectionPool() != null) {
                log.info("Closing target database connection pool...");
                targetAdapter.getConnectionPool().close();
            }

            // Shutdown executor services if available
            if (differ != null) {
                try {
                    log.info("Shutting down TableDiffer...");
                    differ.shutdown();
                    log.info("TableDiffer shutdown completed");
                } catch (Exception e) {
                    log.warn("Error shutting down differ", e);
                }
            }

            // Suggest garbage collection
            System.gc();

            log.info("Resources cleaned up successfully");
        } catch (Exception e) {
            log.warn("Error during resource cleanup", e);
        }
    }

    /**
     * Perform a dry run to validate configuration without executing diff.
     */
    public CliDiffResult performDryRun(CliConfiguration config) throws Exception {
        log.info("Performing dry run for diff operation with strategy: {}, algorithm: {}", 
                config.getStrategyMode(), config.getAlgorithm());

        try {
            // Validate database connections
            DatabaseAdapter sourceAdapter = DatabaseAdapterFactory.createSourceAdapter(config);
            DatabaseAdapter targetAdapter = DatabaseAdapterFactory.createTargetAdapter(config);

            // Test basic connectivity
            long sourceRowCount = sourceAdapter.count(TableSegmentFactory.createSourceTableSegment(config, sourceAdapter));
            long targetRowCount = targetAdapter.count(TableSegmentFactory.createTargetTableSegment(config, targetAdapter));

            log.info("Dry run completed - Source rows: {}, Target rows: {}", sourceRowCount, targetRowCount);

            // Create a dry run result
            TableMetadata tableMetadata = createTableMetadata(config);
            return CliDiffResult.builder()
                    .strategy(config.getStrategyMode())
                    .sourceMissingCount(0)
                    .targetMissingCount(0)
                    .mismatchCount(0)
                    .totalDifferences(0)
                    .sourceRowCount((int) sourceRowCount)
                    .targetRowCount((int) targetRowCount)
                    .differences(new java.util.ArrayList<>())
                    .tableMetadata(tableMetadata)
                    .build();

        } catch (Exception e) {
            log.error("Dry run failed", e);
            throw new Exception("Dry run failed: " + e.getMessage(), e);
        }
    }

    /**
     * Shutdown the service.
     */
    public void shutdown() {
        log.info("DiffService shutdown");
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
