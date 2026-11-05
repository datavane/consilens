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
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column name is required");
        }
        String sanitized = column.replaceAll("[^a-zA-Z0-9_]", "_");
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("column name has no usable characters: " + column);
        }
        // SQL 标识符不能以数字开头；默认列名来自源表列，可能是 "1" 这类带引号标识符。
        char first = sanitized.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            sanitized = "c_" + sanitized;
        }
        return sanitized;
    }

    public static void validateUniqueSanitizedColumns(List<ColumnMapping> columns, String context) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        Set<String> sanitizedNames = new HashSet<>();
        for (ColumnMapping column : columns) {
            if (column == null || column.getName() == null || column.getName().isBlank()) {
                throw new IllegalArgumentException(context + " contains a column without a name");
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
            if (column == null || column.getColumnName() == null || column.getColumnName().isBlank()) {
                throw new IllegalArgumentException(context + " contains a column without a name");
            }
            String normalized = column.getColumnName().toLowerCase();
            if (!names.add(normalized)) {
                throw new IllegalArgumentException(context + " contains duplicate output column: "
                        + column.getColumnName());
            }
        }
    }
}
