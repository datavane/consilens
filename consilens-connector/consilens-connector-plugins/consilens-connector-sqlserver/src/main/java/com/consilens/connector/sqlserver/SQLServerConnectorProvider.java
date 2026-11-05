package com.consilens.connector.sqlserver;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class SQLServerConnectorProvider extends AbstractJdbcConnectorProvider {

    public SQLServerConnectorProvider() {
        super(ConnectorTypes.SQLSERVER, SQLServerDatabaseDialect::new);
    }
}
