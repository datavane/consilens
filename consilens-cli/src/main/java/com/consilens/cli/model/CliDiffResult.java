package com.consilens.cli.model;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Result of a diff operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CliDiffResult {

    /**
     * Strategy used for the diff operation.
     */
    private String strategy;

    /**
     * Duration of the diff operation in milliseconds.
     */
    private Long durationMs;

    /**
     * Timestamp when the operation was completed.
     */
    private LocalDateTime timestamp;

    /**
     * List of all differences found.
     */
    private List<DiffRow> differences;

    /**
     * Top-level metadata about the tables and columns compared.
     */
    @JsonProperty("metadata")
    private TableMetadata tableMetadata;

    /**
     * Information tree for diff process (optional).
     */
    private DiffResult.InfoTree infoTree;

    /**
     * Number of rows missing in source (SOURCE_MISSING operation).
     * -- GETTER --
     *  Get source missing count (SOURCE_MISSING).

     */
    @Getter
    private long sourceMissingCount;

    /**
     * Number of rows missing in target (TARGET_MISSING operation).
     * -- GETTER --
     *  Get target missing count (TARGET_MISSING).

     */
    @Getter
    private long targetMissingCount;

    /**
     * Number of rows with data mismatch (MISMATCH operation).
     * -- GETTER --
     *  Get mismatch count (MISMATCH).

     */
    @Getter
    private long mismatchCount;

    /**
     * Total number of differences.
     */
    private long totalDifferences;

    /**
     * Number of rows processed in source table.
     */
    private long sourceRowCount;

    /**
     * Number of rows processed in target table.
     */
    private long targetRowCount;

    /**
     * Additional metadata about the operation.
     */
    private Map<String, Object> otherMetadata;

    /**
     * Key columns to be used for sorting the results.
     * -- GETTER --
     *  Get sort key columns.

     */
    @Getter
    private List<String> sortKeyColumns;

    /**
     * Get the formatted duration string.
     */
    public String getFormattedDuration() {
        if (durationMs == null) {
            return "Unknown";
        }

        long duration = durationMs;
        if (duration < 1000) {
            return duration + " ms";
        } else if (duration < 60000) {
            return String.format("%.2f seconds", duration / 1000.0);
        } else {
            long minutes = duration / 60000;
            long seconds = (duration % 60000) / 1000;
            return String.format("%d minutes %d seconds", minutes, seconds);
        }
    }

    /**
     * Get the operation summary.
     */
    public String getSummary() {
        return String.format("Found %d differences: %d source missing, %d target missing, %d mismatched",
                totalDifferences, sourceMissingCount, targetMissingCount, mismatchCount);
    }

    /**
     * Check if any differences were found.
     */
    public boolean hasDifferences() {
        return totalDifferences > 0;
    }

    /**
     * Get the percentage of rows that differ (relative to larger table).
     */
    public double getDifferencePercentage() {
        long maxRows = Math.max(sourceRowCount, targetRowCount);
        if (maxRows == 0) {
            return 0.0;
        }
        return (double) totalDifferences / maxRows * 100.0;
    }

    /**
     * Get difference by operation type.
     */
    public List<DiffRow> getDifferencesByOperation(DiffOperation operation) {
        if (differences == null) {
            return List.of();
        }

        return differences.stream()
                .filter(diff -> diff.getOperation() == operation)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get top N differences by primary key.
     */
    public List<DiffRow> getTopDifferences(int limit) {
        if (differences == null || differences.isEmpty()) {
            return List.of();
        }

        return differences.stream()
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Add metadata.
     */
    public void addMetadata(String key, Object value) {
        if (otherMetadata == null) {
            otherMetadata = new HashMap<>();
        }
        otherMetadata.put(key, value);
    }

    /**
     * Get metadata value.
     */
    public Object getMetadata(String key) {
        return otherMetadata != null ? otherMetadata.get(key) : null;
    }

    /**
     * Initialize timestamp to current time.
     */
    public void initializeTimestamp() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    /**
     * Set sort key columns and return this instance for method chaining.
     */
    public CliDiffResult withSortKeyColumns(List<String> sortKeyColumns) {
        this.sortKeyColumns = sortKeyColumns;
        return this;
    }

    /**
     * Get differences sorted by key columns in ascending order.
     */
    public List<DiffRow> getSortedDifferences() {
        if (differences == null || differences.isEmpty() || sortKeyColumns == null || sortKeyColumns.isEmpty()) {
            return differences != null ? differences : new ArrayList<>();
        }

        return differences.stream()
                .sorted(this::compareByKeyColumns)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Compare two DiffRow objects based on sort key columns.
     */
    private int compareByKeyColumns(DiffRow row1, DiffRow row2) {
        List<Object> pk1 = row1.getPrimaryKey();
        List<Object> pk2 = row2.getPrimaryKey();

        // If we have explicit sort key columns, use them
        if (sortKeyColumns != null && !sortKeyColumns.isEmpty()) {
            return compareByKeyColumns(row1, row2, sortKeyColumns);
        }

        // Otherwise, compare by primary key directly
        return comparePrimaryKeyLists(pk1, pk2);
    }

    /**
     * Compare two rows by specific key columns.
     */
    private int compareByKeyColumns(DiffRow row1, DiffRow row2, List<String> keyColumns) {
        for (String keyColumn : keyColumns) {
            Object val1 = getKeyValue(row1, keyColumn);
            Object val2 = getKeyValue(row2, keyColumn);

            int comparison = compareValues(val1, val2);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * Get key value for a specific column from a DiffRow.
     */
    private Object getKeyValue(DiffRow row, String columnName) {
        // Since we don't have access to columnNames1/2 anymore, try to get the value directly
        Object sourceVal = row.getSourceValue(columnName);
        if (sourceVal != null) return sourceVal;

        Object targetVal = row.getTargetValue(columnName);
        return targetVal;
    }

    /**
     * Compare two primary key lists.
     */
    private int comparePrimaryKeyLists(List<Object> pk1, List<Object> pk2) {
        int maxLength = Math.max(pk1.size(), pk2.size());
        for (int i = 0; i < maxLength; i++) {
            Object val1 = i < pk1.size() ? pk1.get(i) : null;
            Object val2 = i < pk2.size() ? pk2.get(i) : null;

            int comparison = compareValues(val1, val2);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /**
     * Compare two values for sorting.
     */
    @SuppressWarnings("unchecked")
    private int compareValues(Object val1, Object val2) {
        if (val1 == null && val2 == null) return 0;
        if (val1 == null) return -1;
        if (val2 == null) return 1;

        // Handle numeric types
        if (val1 instanceof Number && val2 instanceof Number) {
            return java.lang.Double.compare(((Number) val1).doubleValue(), ((Number) val2).doubleValue());
        }

        // Handle string types
        if (val1 instanceof String && val2 instanceof String) {
            return ((String) val1).compareTo((String) val2);
        }

        // Handle comparable objects
        if (val1 instanceof Comparable && val2 instanceof Comparable) {
            try {
                return ((Comparable<Object>) val1).compareTo(val2);
            } catch (ClassCastException e) {
                // Fall back to string comparison
            }
        }

        // Default to string comparison
        return val1.toString().compareTo(val2.toString());
    }
}
