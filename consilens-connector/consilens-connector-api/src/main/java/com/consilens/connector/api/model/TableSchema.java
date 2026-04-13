package com.consilens.connector.api.model;

import lombok.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unified table schema model combining the best implementations from all modules.
 */
@Getter
@Builder
@EqualsAndHashCode
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TableSchema {

    private final TablePath tablePath;
    private final Map<String, ColumnInfo> columns;
    private final String tableName;
    private final String schemaName;

    /**
     * Constructor with validation
     */
    public TableSchema(TablePath tablePath, Map<String, ColumnInfo> columns) {
        if (tablePath == null) {
            throw new IllegalArgumentException("Table path cannot be null");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Columns cannot be null or empty");
        }

        this.tablePath = tablePath;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        this.tableName = tablePath.getTableName();
        this.schemaName = tablePath.getSchema().orElse("public");
    }

    /**
     * Create table schema with table path and columns
     */
    public static TableSchema of(TablePath tablePath, Map<String, ColumnInfo> columns) {
        return new TableSchema(tablePath, columns);
    }

    /**
     * Create empty table schema for testing
     */
    public static TableSchema empty() {
        return TableSchema.of(TablePath.of("test_table"), Collections.emptyMap());
    }

    /**
     * Get column information for a specific column.
     */
    public Optional<ColumnInfo> getColumn(String columnName) {
        return Optional.ofNullable(columns.get(columnName));
    }

    /**
     * Get data type for a specific column.
     */
    public DataType getColumnType(String columnName) {
        ColumnInfo columnInfo = columns.get(columnName);
        return columnInfo != null ? columnInfo.getType() : DataType.UNKNOWN;
    }

    /**
     * Check if a column exists in the schema.
     */
    public boolean hasColumn(String columnName) {
        return columns.containsKey(columnName);
    }

    /**
     * Get all column names in insertion order.
     */
    public java.util.Set<String> getColumnNames() {
        return Collections.unmodifiableSet(columns.keySet());
    }

    /**
     * Get the number of columns.
     */
    public int getColumnCount() {
        return columns.size();
    }

    /**
     * Get all columns as a map.
     */
    public Map<String, ColumnInfo> getColumns() {
        return Collections.unmodifiableMap(columns);
    }

    /**
     * Check if schema is empty.
     */
    public boolean isEmpty() {
        return columns.isEmpty();
    }

    /**
     * Create a new schema with additional columns.
     */
    public TableSchema withAdditionalColumns(Map<String, ColumnInfo> additionalColumns) {
        Map<String, ColumnInfo> newColumns = new LinkedHashMap<>(columns);
        newColumns.putAll(additionalColumns);
        return new TableSchema(tablePath, newColumns);
    }

    /**
     * Create a new schema with a modified column.
     */
    public TableSchema withModifiedColumn(String columnName, ColumnInfo newColumnInfo) {
        Map<String, ColumnInfo> newColumns = new LinkedHashMap<>(columns);
        newColumns.put(columnName, newColumnInfo);
        return new TableSchema(tablePath, newColumns);
    }

    /**
     * Create a new schema with a column removed.
     */
    public TableSchema withoutColumn(String columnName) {
        Map<String, ColumnInfo> newColumns = new LinkedHashMap<>(columns);
        newColumns.remove(columnName);
        return new TableSchema(tablePath, newColumns);
    }

    /**
     * Get primary key columns based on ColumnInfo metadata.
     */
    public java.util.List<String> getPrimaryKeyColumns() {
        return columns.entrySet().stream()
                .filter(entry -> entry.getValue().isPrimaryKey())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get nullable columns.
     */
    public java.util.List<String> getNullableColumns() {
        return columns.entrySet().stream()
                .filter(entry -> entry.getValue().isNullable())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get not nullable columns.
     */
    public java.util.List<String> getNotNullableColumns() {
        return columns.entrySet().stream()
                .filter(entry -> !entry.getValue().isNullable())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Validate if another schema is compatible with this schema.
     */
    public SchemaCompatibilityResult isCompatibleWith(TableSchema otherSchema) {
        if (otherSchema == null) {
            return SchemaCompatibilityResult.incompatible("Other schema is null");
        }

        // Check column count
        if (this.getColumnCount() != otherSchema.getColumnCount()) {
            return SchemaCompatibilityResult.incompatible(
                    String.format("Column count mismatch: %d vs %d",
                            this.getColumnCount(), otherSchema.getColumnCount()));
        }

        // Check column types
        for (String columnName : this.getColumnNames()) {
            if (!otherSchema.hasColumn(columnName)) {
                return SchemaCompatibilityResult.incompatible(
                        String.format("Column %s not found in other schema", columnName));
            }

            DataType thisType = this.getColumnType(columnName);
            DataType otherType = otherSchema.getColumnType(columnName);

            if (!thisType.isCompatibleWith(otherType)) {
                return SchemaCompatibilityResult.incompatible(
                        String.format("Type mismatch for column %s: %s vs %s",
                                columnName, thisType, otherType));
            }
        }

        return SchemaCompatibilityResult.compatible();
    }

    /**
     * Create a deep copy of this schema.
     */
    public TableSchema copy() {
        return new TableSchema(this.tablePath, new LinkedHashMap<>(this.columns));
    }

    /**
     * Schema compatibility result.
     */
    @Getter
    @Builder
    @ToString
    public static class SchemaCompatibilityResult {
        private final boolean compatible;
        private final String message;

        public static SchemaCompatibilityResult compatible() {
            return new SchemaCompatibilityResult(true, "Schemas are compatible");
        }

        public static SchemaCompatibilityResult incompatible(String message) {
            return new SchemaCompatibilityResult(false, message);
        }
    }
}