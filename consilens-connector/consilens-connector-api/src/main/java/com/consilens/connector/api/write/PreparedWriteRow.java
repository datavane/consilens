package com.consilens.connector.api.write;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public final class PreparedWriteRow {

    private final List<PreparedWriteValue> values;

    public PreparedWriteRow(List<PreparedWriteValue> values) {
        this.values = List.copyOf(values);
    }

    public List<PreparedWriteValue> getValues() {
        return values;
    }

    public void bind(PreparedStatement preparedStatement) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            values.get(i).bind(preparedStatement, i + 1);
        }
    }
}
