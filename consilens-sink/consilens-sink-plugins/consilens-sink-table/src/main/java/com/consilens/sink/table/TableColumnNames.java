package com.consilens.sink.table;

import com.consilens.sink.api.model.ColumnMapping;
import com.consilens.connector.api.write.OutputColumnSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TableColumnNames {

    private TableColumnNames() {
    }

    public static String sanitize(String column) {
        return column.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static void validateUniqueSanitizedColumns(List<ColumnMapping> columns, String context) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Set<String> sanitizedNames = new HashSet<>();
        for (ColumnMapping column : columns) {
            if (column == null || column.getName() == null) {
                continue;
            }
            String sanitized = sanitize(column.getName()).toLowerCase();
            if (!sanitizedNames.add(sanitized)) {
                throw new IllegalArgumentException(context + " contains duplicate column name after sanitization: "
                        + column.getName() + " -> " + sanitize(column.getName()));
            }
        }
    }

    public static void validateUniqueOutputColumns(List<OutputColumnSpec> columns, String context) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (OutputColumnSpec column : columns) {
            if (column == null || column.getColumnName() == null) {
                continue;
            }
            String normalized = column.getColumnName().toLowerCase();
            if (!names.add(normalized)) {
                throw new IllegalArgumentException(context + " contains duplicate output column: "
                        + column.getColumnName());
            }
        }
    }
}
