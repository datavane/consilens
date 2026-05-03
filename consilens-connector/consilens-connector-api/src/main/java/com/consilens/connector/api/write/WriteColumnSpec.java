package com.consilens.connector.api.write;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.ConnectorNativeType;

public final class WriteColumnSpec {

    private final String columnName;
    private final TypeDescriptor systemType;
    private final TypeDescriptor targetTypeDescriptor;
    private final ConnectorNativeType targetType;
    private final boolean nullable;

    public WriteColumnSpec(String columnName,
                           TypeDescriptor systemType,
                           TypeDescriptor targetTypeDescriptor,
                           ConnectorNativeType targetType,
                           boolean nullable) {
        this.columnName = columnName;
        this.systemType = systemType;
        this.targetTypeDescriptor = targetTypeDescriptor;
        this.targetType = targetType;
        this.nullable = nullable;
    }

    public String getColumnName() {
        return columnName;
    }

    public TypeDescriptor getSystemType() {
        return systemType;
    }

    public TypeDescriptor getTargetTypeDescriptor() {
        return targetTypeDescriptor;
    }

    public ConnectorNativeType getTargetType() {
        return targetType;
    }

    public boolean isNullable() {
        return nullable;
    }
}
