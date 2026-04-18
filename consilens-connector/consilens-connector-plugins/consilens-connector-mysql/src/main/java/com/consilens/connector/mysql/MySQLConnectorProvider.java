package com.consilens.connector.mysql;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class MySQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public MySQLConnectorProvider() {
        super("mysql", MySQLDatabaseDialect::new);
    }
}
