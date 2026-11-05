package com.consilens.server.application.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Test seam for {@link ConnectionTestServiceImpl}: opens a JDBC connection.
 * Production uses {@link java.sql.DriverManager}; tests inject a stub.
 */
@FunctionalInterface
interface ConnectionOpener {

    Connection open(String url, Properties properties) throws SQLException;
}
