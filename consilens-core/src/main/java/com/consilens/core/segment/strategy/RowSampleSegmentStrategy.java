package com.consilens.core.segment.strategy;

import com.consilens.core.segment.TableSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
public class RowSampleSegmentStrategy implements SegmentStrategy {

    @Override
    public SegmentStrategyType getType() {
        return SegmentStrategyType.ROW_SAMPLE;
    }

    @Override
    public boolean supports(TableSegment table) {
        return table != null;
    }

    @Override
    public CompletableFuture<List<TableSegment>> segment(TableSegment table, int maxSegments, Executor executor) {
        return CompletableFuture.supplyAsync(() -> createRowBasedSegmentsFallback(table, maxSegments), executor);
    }

    private List<TableSegment> createRowBasedSegmentsFallback(TableSegment table, int maxSegments) {
        try {
            long totalRows = table.count();

            if (totalRows == 0) {
                log.debug("Table is empty, returning single segment");
                return List.of(table);
            }

            long rowsPerSegment = Math.max(1, totalRows / maxSegments);

            log.info("Row-based segmentation: total rows={}, max segments={}, rows per segment={}",
                    totalRows, maxSegments, rowsPerSegment);

            List<TableSegment> segments = createRowBasedSegmentsBySampling(table, totalRows, rowsPerSegment);

            log.info("Created {} row-based segments for table: {}", segments.size(), table.getTablePath());

            return segments;

        } catch (Exception e) {
            log.error("Row-based segmentation failed, returning single segment", e);
            return List.of(table);
        }
    }

    private List<TableSegment> createRowBasedSegmentsBySampling(TableSegment table, long totalRows, long rowsPerSegment) {
        List<TableSegment> segments = new ArrayList<>();

        try {
            List<List<Object>> boundaryKeys = sampleKeyBoundaries(table, totalRows, rowsPerSegment);

            if (boundaryKeys.size() < 2) {
                log.warn("Failed to sample enough boundary keys, returning single segment");
                return List.of(table);
            }

            for (int i = 0; i < boundaryKeys.size() - 1; i++) {
                List<Object> minKey = boundaryKeys.get(i);
                Optional<List<Object>> maxKey;

                if (i == boundaryKeys.size() - 2) {
                    maxKey = table.getMaxKey();
                    log.debug("Last segment: using original maxKey={}", maxKey.orElse(null));
                } else {
                    maxKey = Optional.of(boundaryKeys.get(i + 1));
                }

                TableSegment segment = table.toBuilder()
                        .minKey(Optional.of(minKey))
                        .maxKey(maxKey)
                        .segmentIndex(i)
                        .build();

                segments.add(segment);

                log.debug("Created row-based segment {}: minKey={}, maxKey={}", i, minKey, maxKey.orElse(null));
            }

            return segments;

        } catch (Exception e) {
            log.error("Failed to create row-based segments by sampling", e);
            return List.of(table);
        }
    }

    private List<List<Object>> sampleKeyBoundaries(TableSegment table, long totalRows, long rowsPerSegment) {
        List<List<Object>> boundaries = new ArrayList<>();

        try {
            if (table.getDatabase().supportsKeysetSampling()) {
                List<List<Object>> keysetBoundaries = sampleKeyBoundariesWithKeyset(table, totalRows, rowsPerSegment);
                if (!keysetBoundaries.isEmpty()) {
                    return keysetBoundaries;
                }
            }

            List<Long> samplePositions = new ArrayList<>();
            samplePositions.add(0L);

            for (long offset = rowsPerSegment; offset < totalRows; offset += rowsPerSegment) {
                samplePositions.add(offset);
            }

            if (samplePositions.get(samplePositions.size() - 1) != totalRows - 1) {
                samplePositions.add(totalRows - 1);
            }

            for (Long offset : samplePositions) {
                String sql = table.getDatabase().buildKeySamplingQuery(table, offset);
                log.debug("Sampling key at offset {}", offset);

                List<Object> keyValues = executeSamplingQuery(table, sql);
                if (keyValues != null && !keyValues.isEmpty()) {
                    boundaries.add(keyValues);
                }
            }

            log.debug("Sampled {} boundary keys from {} total rows", boundaries.size(), totalRows);

        } catch (Exception e) {
            log.error("Failed to sample key boundaries", e);
        }

        return boundaries;
    }

    private List<List<Object>> sampleKeyBoundariesWithKeyset(TableSegment table, long totalRows, long rowsPerSegment) {
        List<List<Object>> boundaries = new ArrayList<>();

        List<List<Object>> firstRows = executeSamplingQueryRows(table,
                table.getDatabase().buildKeySamplingQuery(table, 0L, 1));
        if (firstRows.isEmpty()) {
            return boundaries;
        }
        List<Object> lastKey = firstRows.get(0);
        if (containsNull(lastKey)) {
            return boundaries;
        }
        boundaries.add(lastKey);

        int maxSegments = (int) Math.max(1, Math.ceil((double) totalRows / rowsPerSegment));
        while (boundaries.size() < maxSegments) {
            String sql = table.getDatabase().buildKeysetSamplingQuery(table, lastKey, rowsPerSegment);
            List<List<Object>> rows = executeSamplingQueryRows(table, sql);
            if (rows.isEmpty()) {
                break;
            }
            List<Object> nextKey = rows.get(rows.size() - 1);
            if (nextKey.equals(lastKey) || containsNull(nextKey)) {
                break;
            }
            boundaries.add(nextKey);
            lastKey = nextKey;
        }

        List<List<Object>> lastRow = executeSamplingQueryRows(table,
                table.getDatabase().buildKeySamplingQuery(table, Math.max(0, totalRows - 1), 1));
        if (!lastRow.isEmpty()) {
            List<Object> finalKey = lastRow.get(0);
            if (!boundaries.isEmpty() && !finalKey.equals(boundaries.get(boundaries.size() - 1))) {
                boundaries.add(finalKey);
            }
        }

        return boundaries;
    }

    private List<Object> executeSamplingQuery(TableSegment table, String sql) {
        List<List<Object>> results = executeSamplingQueryRows(table, sql);
        return results.isEmpty() ? null : results.get(0);
    }

    private List<List<Object>> executeSamplingQueryRows(TableSegment table, String sql) {
        try {
            return table.getDatabase().query(sql, (rs, rowNum) -> {
                List<Object> keyValues = new ArrayList<>();
                for (int i = 1; i <= table.getKeyColumns().size(); i++) {
                    keyValues.add(rs.getObject(i));
                }
                return keyValues;
            });
        } catch (Exception e) {
            log.error("Failed to execute sampling query: {}", sql, e);
            return List.of();
        }
    }

    private boolean containsNull(List<Object> values) {
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }
}
