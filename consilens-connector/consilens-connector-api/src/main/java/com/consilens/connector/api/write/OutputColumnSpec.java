package com.consilens.connector.api.write;

import com.consilens.common.type.TypeDescriptor;

public final class OutputColumnSpec {

    private final String columnName;
    private final TypeDescriptor systemType;
    private final boolean nullable;
    private final String declaredColumnType;

    public OutputColumnSpec(String columnName, TypeDescriptor systemType, boolean nullable, String declaredColumnType) {
        this.columnName = columnName;
        this.systemType = systemType;
        this.nullable = nullable;
        this.declaredColumnType = declaredColumnType;
    }

    public String getColumnName() {
        return columnName;
    }

    public TypeDescriptor getSystemType() {
        return systemType;
    }

    public boolean isNullable() {
        return nullable;
    }

    public String getDeclaredColumnType() {
        return declaredColumnType;
    }
}
