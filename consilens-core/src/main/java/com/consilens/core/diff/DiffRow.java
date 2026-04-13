package com.consilens.core.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unified diff row model representing a single difference.
 */
@Getter
@EqualsAndHashCode
@ToString
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DiffRow {

    /**
     * The operation that was performed.
     */
    private final DiffOperation operation;

    /**
     * The primary key values for this row.
     */
    private final List<Object> primaryKey;

    /**
     * The values from the source table.
     */
    private final Optional<List<Object>> sourceValues;

    /**
     * The values from the target table.
     */
    private final Optional<List<Object>> targetValues;

    /**
     * Column names for table 1 (source table).
     */
    @Getter
    @JsonIgnore
    private final List<String> columnNames1;

    /**
     * Column names for table 2 (target table).
     */
    @Getter
    @JsonIgnore
    private final List<String> columnNames2;

    /**
     * Additional metadata about this difference.
     */
    private final java.util.Map<String, Object> metadata;

    /**
     * Create a diff row for an added row (source missing).
     */
    public static DiffRow added(List<Object> primaryKey, List<Object> targetValues, List<String> columnNames2) {
        return builder()
                .operation(DiffOperation.SOURCE_MISSING)
                .primaryKey(primaryKey)
                .sourceValues(Optional.empty())
                .targetValues(Optional.of(targetValues))
                .columnNames1(Collections.emptyList())
                .columnNames2(columnNames2)
                .metadata(new java.util.HashMap<>())
                .build();
    }

    /**
     * Create a diff row for a removed row (target missing).
     */
    public static DiffRow removed(List<Object> primaryKey, List<Object> sourceValues, List<String> columnNames1) {
        return builder()
                .operation(DiffOperation.TARGET_MISSING)
                .primaryKey(primaryKey)
                .sourceValues(Optional.of(sourceValues))
                .targetValues(Optional.empty())
                .columnNames1(columnNames1)
                .columnNames2(Collections.emptyList())
                .metadata(new java.util.HashMap<>())
                .build();
    }

    /**
     * Create a diff row for a modified row (data mismatch).
     */
    public static DiffRow modified(List<Object> primaryKey, List<Object> sourceValues,
                                  List<Object> targetValues, List<String> columnNames1, List<String> columnNames2) {
        return builder()
                .operation(DiffOperation.MISMATCH)
                .primaryKey(primaryKey)
                .sourceValues(Optional.of(sourceValues))
                .targetValues(Optional.of(targetValues))
                .columnNames1(columnNames1)
                .columnNames2(columnNames2)
                .metadata(new java.util.HashMap<>())
                .build();
    }

    /**
     * Create a diff row for a modified row with specific changed columns (data mismatch).
     */
    public static DiffRow modified(List<Object> primaryKey, List<Object> sourceValues,
                                  List<Object> targetValues, List<String> columnNames1, List<String> columnNames2,
                                  List<Integer> changedColumnIndices) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("changedColumnIndices", changedColumnIndices);

        return builder()
                .operation(DiffOperation.MISMATCH)
                .primaryKey(primaryKey)
                .sourceValues(Optional.of(sourceValues))
                .targetValues(Optional.of(targetValues))
                .columnNames1(columnNames1)
                .columnNames2(columnNames2)
                .metadata(metadata)
                .build();
    }

    /**
     * Create a diff row for a modified row with separate changed columns for table1 and table2 (data mismatch).
     */
    public static DiffRow modified(List<Object> primaryKey, List<Object> sourceValues,
                                  List<Object> targetValues, List<String> columnNames1, List<String> columnNames2,
                                  List<String> changedColumns1, List<String> changedColumns2) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("changedColumns1", changedColumns1);
        metadata.put("changedColumns2", changedColumns2);

        return builder()
                .operation(DiffOperation.MISMATCH)
                .primaryKey(primaryKey)
                .sourceValues(Optional.of(sourceValues))
                .targetValues(Optional.of(targetValues))
                .columnNames1(columnNames1)
                .columnNames2(columnNames2)
                .metadata(metadata)
                .build();
    }

    /**
     * Get all values from the source table.
     */
    public List<Object> getAllSourceValues() {
        return sourceValues.orElse(Collections.emptyList());
    }

    /**
     * Get all values from the target table.
     */
    public List<Object> getAllTargetValues() {
        return targetValues.orElse(Collections.emptyList());
    }

    /**
     * Get the value for a specific column from source.
     */
    public Object getSourceValue(String columnName) {
        if (sourceValues.isEmpty()) {
            return null;
        }

        int index = columnNames1.indexOf(columnName);
        return index >= 0 ? sourceValues.get().get(index) : null;
    }

    /**
     * Get the value for a specific column from target.
     */
    public Object getTargetValue(String columnName) {
        if (targetValues.isEmpty()) {
            return null;
        }

        int index = columnNames2.indexOf(columnName);
        return index >= 0 ? targetValues.get().get(index) : null;
    }

    /**
     * Check if a specific column changed.
     */
    public boolean isColumnChanged(String columnName) {
        Object sourceVal = getSourceValue(columnName);
        Object targetVal = getTargetValue(columnName);
        return !java.util.Objects.equals(sourceVal, targetVal);
    }

    /**
     * Get list of changed column names for table1.
     */
    @JsonIgnore
    public List<String> getChangedColumns1() {
        return (List<String>) metadata.getOrDefault("changedColumns1", Collections.emptyList());
    }

    /**
     * Get list of changed column names for table2.
     */
    @JsonIgnore
    public List<String> getChangedColumns2() {
        return (List<String>) metadata.getOrDefault("changedColumns2", Collections.emptyList());
    }

    /**
     * Get primary key as string representation.
     */
    public String getPrimaryKeyString() {
        return primaryKey.stream()
                .map(obj -> obj != null ? obj.toString() : "null")
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Check if this diff row represents a structural change.
     */
    public boolean isStructuralChange() {
        return operation == DiffOperation.SOURCE_MISSING || operation == DiffOperation.TARGET_MISSING;
    }

    /**
     * Check if this diff row represents a data change.
     */
    public boolean isDataChange() {
        return operation == DiffOperation.MISMATCH;
    }

    /**
     * Add metadata to this diff row.
     */
    public DiffRow withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new java.util.HashMap<>(metadata);
        newMetadata.put(key, value);

        return builder()
                .operation(operation)
                .primaryKey(primaryKey)
                .sourceValues(sourceValues)
                .targetValues(targetValues)
                .columnNames1(columnNames1)
                .columnNames2(columnNames2)
                .metadata(newMetadata)
                .build();
    }

    /**
     * Create a copy with different operation.
     */
    public DiffRow withOperation(DiffOperation newOperation) {
        return builder()
                .operation(newOperation)
                .primaryKey(primaryKey)
                .sourceValues(sourceValues)
                .targetValues(targetValues)
                .columnNames1(columnNames1)
                .columnNames2(columnNames2)
                .metadata(metadata)
                .build();
    }

    /**
     * Get a human-readable description of this difference.
     */
    public String getDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(operation.getDisplayName()).append(": ");
        sb.append("PK = ").append(getPrimaryKeyString());

        if (isDataChange()) {
            List<String> changedColumns1 = getChangedColumns1();
            List<String> changedColumns2 = getChangedColumns2();

            if (!changedColumns1.isEmpty() && !changedColumns2.isEmpty()) {
                sb.append(", Changed columns: ").append(String.join(", ", changedColumns1))
                  .append(" → ").append(String.join(", ", changedColumns2));
            } else if (!changedColumns1.isEmpty()) {
                sb.append(", Changed columns: ").append(String.join(", ", changedColumns1));
            } else if (!changedColumns2.isEmpty()) {
                sb.append(", Changed columns: ").append(String.join(", ", changedColumns2));
            }
        }

        return sb.toString();
    }

    /**
     * Convert to array format for backward compatibility.
     */
    public Object[] toArray() {
        Object[] result = new Object[2];
        result[0] = operation.getCode();
        result[1] = operation == DiffOperation.SOURCE_MISSING ? getAllTargetValues().toArray() :
                     operation == DiffOperation.TARGET_MISSING ? getAllSourceValues().toArray() :
                             Collections.singletonList(getPrimaryKeyString());
        return result;
    }

    /**
     * Create a diff row from array format for backward compatibility.
     */
    public static DiffRow fromArray(Object[] array) {
        if (array == null || array.length < 2) {
            throw new IllegalArgumentException("Array must have at least 2 elements");
        }

        String operationCode = (String) array[0];
        DiffOperation operation = DiffOperation.fromCode(operationCode);
        Object[] rowData = (Object[]) array[1];

        switch (operation) {
            case SOURCE_MISSING:
                // For added rows, assume the row data is the primary key + values
                if (rowData.length > 0) {
                    List<Object> primaryKey = Collections.singletonList(rowData[0]);
                    List<Object> values = Arrays.asList(Arrays.copyOfRange(rowData, 0, rowData.length));
                    List<String> columnNames2 = java.util.stream.IntStream.range(0, values.size())
                            .mapToObj(i -> "column_" + i)
                            .collect(java.util.stream.Collectors.toList());
                    return added(primaryKey, values, columnNames2);
                }
                break;

            case TARGET_MISSING:
                // Similar logic for removed rows
                if (rowData.length > 0) {
                    List<Object> primaryKey = Collections.singletonList(rowData[0]);
                    List<Object> values = Arrays.asList(Arrays.copyOfRange(rowData, 0, rowData.length));
                    List<String> columnNames1 = java.util.stream.IntStream.range(0, values.size())
                            .mapToObj(i -> "column_" + i)
                            .collect(java.util.stream.Collectors.toList());
                    return removed(primaryKey, values, columnNames1);
                }
                break;

            case MISMATCH:
                // For modified rows, need both source and target
                // This is simplified - in practice, you'd need both sets of values
                if (rowData.length > 0) {
                    List<Object> primaryKey = Collections.singletonList(rowData[0]);
                    List<Object> values = Arrays.asList(Arrays.copyOfRange(rowData, 0, rowData.length));
                    List<String> columnNames1 = java.util.stream.IntStream.range(0, values.size())
                            .mapToObj(i -> "column_" + i)
                            .collect(java.util.stream.Collectors.toList());
                    return modified(primaryKey, values, values, columnNames1, columnNames1);
                }
                break;
        }

        throw new IllegalArgumentException("Invalid diff row format for operation: " + operation);
    }
}