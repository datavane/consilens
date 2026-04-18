package com.consilens.connector.api.dataset;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.TablePath;

import java.sql.Connection;
import java.sql.SQLException;

public interface RelationalDatasetSupport {

    String getName();

    /** Returns the connector type identifier (e.g. "mysql", "postgresql"). */
    String getConnectorType();

    String getJdbcUrl();

    String getUsername();

    DatabaseDialect getDialect();

    TablePath getTablePath();

    Connection getConnection() throws SQLException;
}
