package com.consilens.core.compare.relational;

import com.consilens.connector.api.LegacyTypeMapper;
import com.consilens.connector.api.model.ColumnInfo;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.TableSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RelationalSchemaAdapter {

    private RelationalSchemaAdapter() {
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
                        .type(field.getTypeDescriptor() != null
                                ? LegacyTypeMapper.toLegacyDataType(field.getTypeDescriptor())
                                : resolveDataType(field.getCanonicalType()))
                        .nullable(field.isNullable())
                        .precision(Optional.empty())
                        .scale(Optional.empty())
                        .maxLength(Optional.empty())
                        .defaultValue(Optional.empty())
                        .ordinalPosition(ordinal++)
                        .collation(Optional.empty())
                        .comment(Optional.empty())
                        .primaryKey(false)
                        .uniqueKey(false)
                        .build());
            }
        }
        return columns.isEmpty() ? null : new TableSchema(tablePath, columns);
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
