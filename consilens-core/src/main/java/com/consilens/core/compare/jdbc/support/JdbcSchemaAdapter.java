package com.consilens.core.compare.jdbc.support;

import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JdbcSchemaAdapter {

    private JdbcSchemaAdapter() {
    }

    public static SchemaDescriptor adapt(TableSchema tableSchema) {
        List<FieldDescriptor> fields = new ArrayList<>();
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();

        tableSchema.getColumns().forEach((name, columnInfo) -> {
            FieldDescriptor fieldDescriptor = FieldDescriptor.builder()
                    .name(name)
                    .canonicalType(columnInfo.getType() != null ? columnInfo.getType().name().toLowerCase() : null)
                    .nullable(columnInfo.isNullable())
                    .ordinal(columnInfo.getOrdinalPosition())
                    .attributes(new LinkedHashMap<>())
                    .build();
            fields.add(fieldDescriptor);
            fieldMap.put(name, fieldDescriptor);
        });

        return SchemaDescriptor.builder()
                .fields(fields)
                .fieldMap(fieldMap)
                .build();
    }

    public static TableSchema toLegacySchema(SchemaDescriptor schemaDescriptor, TablePath tablePath) {
        if (schemaDescriptor == null) {
            return null;
        }

        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        if (schemaDescriptor.getFields() != null) {
            int ordinal = 1;
            for (FieldDescriptor field : schemaDescriptor.getFields()) {
                if (field == null || field.getName() == null) {
                    continue;
                }
                columns.put(field.getName(), ColumnInfo.builder()
                        .name(field.getName())
                        .type(resolveDataType(field.getCanonicalType()))
                        .nullable(field.isNullable())
                        .precision(java.util.Optional.empty())
                        .scale(java.util.Optional.empty())
                        .maxLength(java.util.Optional.empty())
                        .defaultValue(java.util.Optional.empty())
                        .ordinalPosition(ordinal++)
                        .collation(java.util.Optional.empty())
                        .comment(java.util.Optional.empty())
                        .primaryKey(false)
                        .uniqueKey(false)
                        .build());
            }
        }
        return new TableSchema(tablePath, columns);
    }

    private static DataType resolveDataType(String canonicalType) {
        if (canonicalType == null || canonicalType.trim().isEmpty()) {
            return DataType.UNKNOWN;
        }
        try {
            return DataType.valueOf(canonicalType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DataType.UNKNOWN;
        }
    }
}
