package com.consilens.connector.postgresql;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PostgreSQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public PostgreSQLConnectorProvider() {
        super("postgresql", PostgreSQLDatabaseDialect::new);
    }
}
