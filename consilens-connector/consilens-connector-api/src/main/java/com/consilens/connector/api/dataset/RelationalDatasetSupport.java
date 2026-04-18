package com.consilens.connector.api.dataset;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.TablePath;

import java.sql.Connection;
import java.sql.SQLException;

public interface RelationalDatasetSupport {

    String getName();

    DatabaseType getDatabaseType();

    String getJdbcUrl();

    String getUsername();

    DatabaseDialect getDialect();

    TablePath getTablePath();

    Connection getConnection() throws SQLException;
}
