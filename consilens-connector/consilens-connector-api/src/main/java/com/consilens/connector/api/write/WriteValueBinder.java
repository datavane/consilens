package com.consilens.connector.api.write;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@FunctionalInterface
public interface WriteValueBinder {

    void bind(PreparedStatement preparedStatement, int index, Object value, Integer jdbcType) throws SQLException;
}
