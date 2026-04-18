package com.consilens.connector.sqlserver;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class SQLServerConnectorProvider extends AbstractJdbcConnectorProvider {

    public SQLServerConnectorProvider() {
        super("sqlserver", SQLServerDatabaseDialect::new);
    }
}
