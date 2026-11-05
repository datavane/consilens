package com.consilens.core.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

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
@Builder(toBuilder = true)
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
     * 附加额外元数据（重复键场景的 occurrence / keyMultiplicity）。
     */
    public static DiffRow withExtraMetadata(DiffRow row, java.util.Map<String, Object> extra) {
        if (row == null || extra == null || extra.isEmpty()) {
            return row;
        }
        java.util.Map<String, Object> merged = new java.util.HashMap<>(
                row.getMetadata() != null ? row.getMetadata() : java.util.Map.of());
        merged.putAll(extra);
        return row.toBuilder().metadata(merged).build();
    }

    /**
     * 容差内行的 metadata 标记：{@code true} 表示"精确比较不等、但按容差判等"。
     * 默认口径下这类行不计入差异，只作为报告样本保留。
     */
    public static final String METADATA_WITHIN_TOLERANCE = "withinTolerance";

    /** 触发容差判等的列名（报告里单列时说明"为什么不算差异"）。 */
    public static final String METADATA_TOLERANCE_COLUMNS = "toleranceColumns";

    /** 各列的实际差值（列名 → 绝对差），供报告展示"差多少但在容差内"。 */
    public static final String METADATA_TOLERANCE_DELTAS = "toleranceDeltas";

    /**
     * 构造一行"容差内"记录：操作仍是 MISMATCH，但带 {@link #METADATA_WITHIN_TOLERANCE} 标记，
     * 由调用方按 {@code withinTolerance} 口径决定计数还是只列出。
     */
    public static DiffRow withinTolerance(List<Object> primaryKey, List<Object> sourceValues,
                                          List<Object> targetValues, List<String> columnNames1,
                                          List<String> columnNames2, List<String> toleranceColumns1,
                                          List<String> toleranceColumns2,
                                          java.util.Map<String, Double> toleranceDeltas) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put(METADATA_WITHIN_TOLERANCE, Boolean.TRUE);
        metadata.put(METADATA_TOLERANCE_COLUMNS, toleranceColumns1);
        // REPORT 口径下这行会被当普通差异输出，changedColumns 必须齐全，否则报告只说"变了"不说是哪列；
        // 两侧列名可能不同（comparison.mappings 改名），必须各写各的
        metadata.put("changedColumns1", toleranceColumns1);
        metadata.put("changedColumns2", toleranceColumns2);
        if (toleranceDeltas != null && !toleranceDeltas.isEmpty()) {
            metadata.put(METADATA_TOLERANCE_DELTAS, toleranceDeltas);
        }
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

    /** 是否为"容差内"记录（metadata 标记；空 metadata 视为否）。 */
    @JsonIgnore
    public boolean isWithinTolerance() {
        return metadata != null && Boolean.TRUE.equals(metadata.get(METADATA_WITHIN_TOLERANCE));
    }

    /** 两侧值数组同序（键列 + 比对列）时适用的列名：一侧缺失的行只在另一侧带列名，
     * 因此空集合表示"这一侧没有列名"，不能等同于 null。
     */
    public List<String> resolvedColumnNames() {
        if (columnNames1 != null && !columnNames1.isEmpty()) {
            return columnNames1;
        }
        return columnNames2 != null ? columnNames2 : List.of();
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
     * Check if this diff row represents a data change.
     */
    public boolean isDataChange() {
        return operation == DiffOperation.MISMATCH;
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
}