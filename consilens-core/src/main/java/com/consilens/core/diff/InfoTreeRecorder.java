package com.consilens.core.diff;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe recorder for building InfoTree snapshots.
 */
public class InfoTreeRecorder {

    private final String rootNodeId;
    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>();
    private final Map<String, Object> metrics = new ConcurrentHashMap<>();
    private final Map<String, Long> stageTimeMs = new ConcurrentHashMap<>();
    private final AtomicInteger totalNodes = new AtomicInteger(0);
    private final AtomicLong maxDepth = new AtomicLong(0);
    private final AtomicInteger totalSegments = new AtomicInteger(0);
    private final AtomicInteger processedSegments = new AtomicInteger(0);
    private final AtomicInteger skippedSegments = new AtomicInteger(0);
    private final AtomicInteger localCompareSegments = new AtomicInteger(0);
    private final AtomicInteger bisectSegments = new AtomicInteger(0);
    private final AtomicInteger adaptiveSegments = new AtomicInteger(0);
    private final AtomicLong totalRowsScanned = new AtomicLong(0);
    private final AtomicLong totalDifferences = new AtomicLong(0);
    private final long startTime;
    private long endTime;

    public InfoTreeRecorder(String rootNodeId) {
        this.rootNodeId = rootNodeId;
        this.startTime = System.currentTimeMillis();
    }

    public void startNode(String nodeId, String parentNodeId, String name, String phase, String segmentId, long depth) {
        nodes.computeIfAbsent(nodeId, id -> {
            NodeRecord record = new NodeRecord();
            record.nodeId = id;
            record.parentNodeId = parentNodeId;
            record.name = name;
            record.phase = phase;
            record.segmentId = segmentId;
            record.depth = depth;
            record.startedAt = System.currentTimeMillis();
            totalNodes.incrementAndGet();
            updateMaxDepth(depth);
            if ("segment".equalsIgnoreCase(phase)) {
                totalSegments.incrementAndGet();
                processedSegments.incrementAndGet();
            }
            return record;
        });
    }

    public void endNode(String nodeId) {
        NodeRecord record = nodes.get(nodeId);
        if (record != null) {
            record.endedAt = System.currentTimeMillis();
        }
    }

    public void endTree() {
        this.endTime = System.currentTimeMillis();
    }

    public void recordRange(String nodeId, String minKey, String maxKey, String whereClause) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.minKey = minKey;
        record.maxKey = maxKey;
        record.whereClause = whereClause;
    }

    public void recordCounts(String nodeId, long sourceCount, long targetCount) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.sourceCount = sourceCount;
        record.targetCount = targetCount;
        record.maxCount = Math.max(sourceCount, targetCount);
        totalRowsScanned.addAndGet(sourceCount + targetCount);
    }

    public void recordDecision(String nodeId, String decisionReason) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.decisionReason = decisionReason;
    }

    public void recordSplit(String nodeId, String splitType, int splitFactor) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.splitType = splitType;
        record.splitFactor = splitFactor;
        record.splitTimes++;
    }

    public void addDiffCounts(String nodeId, long diffCount, long sourceMissing, long targetMissing, long mismatch) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.diffCount += diffCount;
        record.sourceMissing += sourceMissing;
        record.targetMissing += targetMissing;
        record.mismatch += mismatch;
        totalDifferences.addAndGet(diffCount);
    }

    public void addQueryCount(String nodeId, long delta) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.queryCount += delta;
    }

    public void addRowsFetched(String nodeId, long delta) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.rowsFetched += delta;
    }

    public void addBytesFetched(String nodeId, long delta) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.bytesFetched += delta;
    }

    public void markSkippedSegment() {
        skippedSegments.incrementAndGet();
    }

    public void markLocalCompareSegment() {
        localCompareSegments.incrementAndGet();
    }

    public void markBisectSegment() {
        bisectSegments.incrementAndGet();
    }

    public void markAdaptiveSegment() {
        adaptiveSegments.incrementAndGet();
    }

    public void addStageTime(String stage, long durationMs) {
        if (stage == null) {
            return;
        }
        stageTimeMs.merge(stage, durationMs, Long::sum);
    }

    public void recordMetric(String nodeId, String key, Object value) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.metrics.put(key, value);
    }

    public void addMetric(String nodeId, String key, long delta) {
        NodeRecord record = nodes.get(nodeId);
        if (record == null) {
            return;
        }
        record.metrics.compute(key, (k, v) -> {
            long base = 0L;
            if (v instanceof Number) {
                base = ((Number) v).longValue();
            }
            return base + delta;
        });
    }

    public void recordGlobalMetric(String key, Object value) {
        metrics.put(key, value);
    }

    public DiffResult.InfoTree snapshot() {
        List<DiffResult.InfoTreeNode> snapshotNodes = new ArrayList<>();
        for (NodeRecord record : nodes.values()) {
            long endedAt = record.endedAt > 0 ? record.endedAt : System.currentTimeMillis();
            long duration = record.startedAt > 0 ? Math.max(0, endedAt - record.startedAt) : 0L;
            snapshotNodes.add(DiffResult.InfoTreeNode.builder()
                    .nodeId(record.nodeId)
                    .parentNodeId(record.parentNodeId)
                    .name(record.name)
                    .phase(record.phase)
                    .segmentId(record.segmentId)
                    .depth(record.depth)
                    .minKey(record.minKey)
                    .maxKey(record.maxKey)
                    .whereClause(record.whereClause)
                    .sourceCount(record.sourceCount)
                    .targetCount(record.targetCount)
                    .maxCount(record.maxCount)
                    .splitType(record.splitType)
                    .splitFactor(record.splitFactor)
                    .splitTimes(record.splitTimes)
                    .queryCount(record.queryCount)
                    .rowsFetched(record.rowsFetched)
                    .bytesFetched(record.bytesFetched)
                    .diffCount(record.diffCount)
                    .sourceMissing(record.sourceMissing)
                    .targetMissing(record.targetMissing)
                    .mismatch(record.mismatch)
                    .decisionReason(record.decisionReason)
                    .error(record.error)
                    .startedAt(record.startedAt)
                    .endedAt(record.endedAt)
                    .durationMs(duration)
                    .metrics(new HashMap<>(record.metrics))
                    .build());
        }

        return DiffResult.InfoTree.builder()
                .rootNodeId(Optional.ofNullable(rootNodeId).orElse(""))
                .totalNodes(totalNodes.get())
                .maxDepth(maxDepth.get())
                .startTime(startTime)
                .endTime(endTime > 0 ? endTime : System.currentTimeMillis())
                .durationMs((endTime > 0 ? endTime : System.currentTimeMillis()) - startTime)
                .totalSegments(totalSegments.get())
                .processedSegments(processedSegments.get())
                .skippedSegments(skippedSegments.get())
                .localCompareSegments(localCompareSegments.get())
                .bisectSegments(bisectSegments.get())
                .adaptiveSegments(adaptiveSegments.get())
                .totalRowsScanned(totalRowsScanned.get())
                .totalDifferences(totalDifferences.get())
                .stageTimeMs(new HashMap<>(stageTimeMs))
                .metrics(new HashMap<>(metrics))
                .nodes(snapshotNodes)
                .build();
    }

    private void updateMaxDepth(long depth) {
        maxDepth.updateAndGet(current -> Math.max(current, depth));
    }

    @Data
    private static class NodeRecord {
        private String nodeId;
        private String parentNodeId;
        private String name;
        private String phase;
        private String segmentId;
        private long depth;
        private String minKey;
        private String maxKey;
        private String whereClause;
        private long sourceCount;
        private long targetCount;
        private long maxCount;
        private String splitType;
        private int splitFactor;
        private int splitTimes;
        private long queryCount;
        private long rowsFetched;
        private long bytesFetched;
        private long diffCount;
        private long sourceMissing;
        private long targetMissing;
        private long mismatch;
        private String decisionReason;
        private String error;
        private long startedAt;
        private long endedAt;
        private Map<String, Object> metrics = new ConcurrentHashMap<>();
    }
}
