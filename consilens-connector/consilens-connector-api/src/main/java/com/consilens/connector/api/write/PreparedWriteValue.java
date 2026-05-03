package com.consilens.connector.api.write;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class PreparedWriteValue {

    private final Object value;
    private final Integer jdbcType;
    private final WriteValueBinder binder;

    public PreparedWriteValue(Object value, Integer jdbcType, WriteValueBinder binder) {
        this.value = value;
        this.jdbcType = jdbcType;
        this.binder = binder;
    }

    public Object getValue() {
        return value;
    }

    public Integer getJdbcType() {
        return jdbcType;
    }

    public void bind(PreparedStatement preparedStatement, int index) throws SQLException {
        binder.bind(preparedStatement, index, value, jdbcType);
    }
}
