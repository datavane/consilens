package com.consilens.connector.api.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TypedOutputRow {

    private final List<TypedOutputValue> values;
    private final Map<String, TypedOutputValue> byColumnName;

    public TypedOutputRow(List<TypedOutputValue> values) {
        this.values = List.copyOf(values);
        Map<String, TypedOutputValue> map = new LinkedHashMap<>();
        for (TypedOutputValue value : values) {
            map.put(value.getColumnName(), value);
        }
        this.byColumnName = Map.copyOf(map);
    }

    public List<TypedOutputValue> getValues() {
        return values;
    }

    public TypedOutputValue getValue(String columnName) {
        return byColumnName.get(columnName);
    }
}
