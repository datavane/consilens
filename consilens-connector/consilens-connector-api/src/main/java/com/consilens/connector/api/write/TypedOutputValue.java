package com.consilens.connector.api.write;

import com.consilens.common.type.TypeDescriptor;

public final class TypedOutputValue {

    private final String columnName;
    private final TypeDescriptor systemType;
    private final Object value;

    public TypedOutputValue(String columnName, TypeDescriptor systemType, Object value) {
        this.columnName = columnName;
        this.systemType = systemType;
        this.value = value;
    }

    public String getColumnName() {
        return columnName;
    }

    public TypeDescriptor getSystemType() {
        return systemType;
    }

    public Object getValue() {
        return value;
    }
}
