package com.consilens.core.segmentation;

import com.consilens.core.segment.TableSegment;
import com.consilens.core.database.adpter.DatabaseAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Distribution-aware table segmenter that splits a table into optimal segments for parallel comparison.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li><b>Checkpoint selection</b> — uses {@link CheckpointSelector} to pick boundary keys
 *       that produce roughly equal-sized segments</li>
 *   <li><b>Multi-type keys</b> — supports both numeric and string primary keys</li>
 * </ul>
 *
 * <p>Usage: create an instance per database adapter, then call
 * {@link #segmentTable(TableSegment, int, Executor)} to asynchronously produce segments.
 *
 * @see CheckpointSelector
 * @see com.consilens.core.segment.TableSegment
 */
@Slf4j
public class IntelligentSegmenter {

    private final CheckpointSelector checkpointSelector;
    private final DatabaseAdapter databaseAdapter;

    /**
     * Creates a segmenter with full configuration.
     *
     * @param databaseAdapter    the database adapter for executing segmentation queries
     * @param checkpointSelector strategy for selecting split-point keys
     */
    public IntelligentSegmenter(DatabaseAdapter databaseAdapter, CheckpointSelector checkpointSelector) {
        this.databaseAdapter = databaseAdapter;
        this.checkpointSelector = checkpointSelector;
    }

    /**
     * Asynchronously segments a table into at most {@code maxSegments} non-overlapping slices.
     *
     * @param table       the bounded table segment to split
     * @param maxSegments maximum number of segments to produce
     * @param executor    executor for running the segmentation task
     * @return a future that resolves to the list of table segments
     */
    public CompletableFuture<List<TableSegment>> segmentTable(TableSegment table, int maxSegments, Executor executor) {
        return CompletableFuture.supplyAsync(() -> segmentTableInternal(table, maxSegments), executor);
    }

    private List<TableSegment> segmentTableInternal(TableSegment table, int maxSegments) {
        try {
            log.debug("Starting intelligent segmentation of table: {}", table.getTablePath());

            // Ensure table is bounded
            if (!table.isBounded()) {
                log.warn("Table segment is not bounded, creating single segment");
                return List.of(table);
            }

            // Extract key bounds
            KeyVector minKey = KeyVector.fromKeyObject(table.getMinKey().orElse(null));
            KeyVector maxKey = KeyVector.fromKeyObject(table.getMaxKey().orElse(null));

            if (minKey == null || maxKey == null) {
                log.warn("Cannot extract key bounds, creating single segment");
                return List.of(table);
            }

            // Check key column types - we support both numeric and string types
            // For mixed types, we'll use the first numeric column for segmentation
            int numericDimensionIndex = -1;
            try {
                for (int i = 0; i < minKey.getDimensions(); i++) {
                    Comparable minVal = minKey.getValue(i);
                    Comparable maxVal = maxKey.getValue(i);

                    // Find the first numeric dimension for segmentation
                    if (minVal instanceof Number && maxVal instanceof Number) {
                        numericDimensionIndex = i;
                        log.debug("Found numeric key column at dimension {}: {} to {}", i, minVal, maxVal);
                        break;
                    }
                }

                // If no numeric columns found, we can still segment using string comparison
                // but log a warning about potential performance
                if (numericDimensionIndex == -1) {
                    log.debug("No numeric key columns found, will use string-based segmentation");
                    // Don't return early - we can still segment using string comparison
                }
            } catch (Exception e) {
                log.warn("Error analyzing key column types", e);
                return List.of(table);
            }

            // Choose checkpoints
            List<KeyVector> checkpoints = checkpointSelector.chooseCheckpoints(
                    minKey,
                    maxKey,
                    Math.max(2, maxSegments + 1),
                    true);

            // Validate checkpoints
            if (!checkpointSelector.validateCheckpoints(checkpoints, minKey, maxKey)) {
                log.warn("Checkpoint validation failed, using fallback strategy");
                checkpoints = List.of(minKey, maxKey);
            }

            // Create segments
            List<TableSegment> segments = createSegmentsFromCheckpoints(table, checkpoints);

            log.debug("Created {} segments for table: {}", segments.size(), table.getTablePath());

            return segments;

        } catch (Exception e) {
            log.error("Error during intelligent segmentation", e);
            return List.of(table); // Fallback to original segment
        }
    }

    /**
     * Estimate table size using database statistics.
     */
    private long estimateTableSize(TableSegment table) {
        try {
            long rows = table.getDatabase().count(table);
            log.debug("Estimated table size using COUNT query: {}", rows);
            return rows;

        } catch (Exception e) {
            log.warn("Failed to estimate table size, using default", e);
            return 1000000; // Default estimate
        }
    }

    /**
     * Create table segments from checkpoints.
     */
    private List<TableSegment> createSegmentsFromCheckpoints(TableSegment originalTable, List<KeyVector> checkpoints) {
        List<TableSegment> segments = new ArrayList<>();

        // Log the original table's database adapter for debugging
        log.debug("Creating segments from checkpoints for table: {}, database: {}", 
                originalTable.getTablePath(), 
                originalTable.getDatabase() != null ? originalTable.getDatabase().getName() : "null");

        for (int i = 0; i < checkpoints.size() - 1; i++) {
            KeyVector start = checkpoints.get(i);
            KeyVector end = checkpoints.get(i + 1);
            boolean isLast = i == checkpoints.size() - 2;

            TableSegment segment = originalTable.toBuilder()
                    .minKey(Optional.of(new ArrayList<>(start.toList())))
                    .maxKey(Optional.of(new ArrayList<>(end.toList())))
                    // 中间段上界排他，避免相邻段共享边界行；最后一段继承父段（根段含上界保证最大行不丢）
                    .upperBoundInclusive(isLast ? originalTable.isUpperBoundInclusive() : false)
                    .build();

            // Verify the database adapter is preserved
            if (segment.getDatabase() == null) {
                log.error("BUG: Database adapter lost after toBuilder() for segment {}", i);
            } else if (segment.getDatabase() != originalTable.getDatabase()) {
                log.error("BUG: Database adapter changed after toBuilder() for segment {}: original={}, new={}", 
                        i, originalTable.getDatabase().getName(), segment.getDatabase().getName());
            } else {
                log.debug("Segment {} created with correct database: {}", i, segment.getDatabase().getName());
            }

            segments.add(segment);
        }

        return segments;
    }

    /**
     * Get segment statistics.
     */
    /**
     * Computes statistics (count, average/min/max size) for a list of segments.
     *
     * @param segments the segments to analyze
     * @return aggregated statistics
     */
    public SegmentStatistics getSegmentStatistics(List<TableSegment> segments) {
        long totalEstimatedRows = 0;
        long maxSegmentSize = 0;
        long minSegmentSize = Long.MAX_VALUE;

        for (TableSegment segment : segments) {
            long estimatedSize = estimateTableSize(segment);
            totalEstimatedRows += estimatedSize;
            maxSegmentSize = Math.max(maxSegmentSize, estimatedSize);
            minSegmentSize = Math.min(minSegmentSize, estimatedSize);
        }

        double avgSegmentSize = segments.isEmpty() ? 0 : (double) totalEstimatedRows / segments.size();

        return SegmentStatistics.builder()
                .segmentCount(segments.size())
                .totalEstimatedRows(totalEstimatedRows)
                .averageSegmentSize(avgSegmentSize)
                .maxSegmentSize(maxSegmentSize)
                .minSegmentSize(minSegmentSize == Long.MAX_VALUE ? 0 : minSegmentSize)
                .build();
    }
}
