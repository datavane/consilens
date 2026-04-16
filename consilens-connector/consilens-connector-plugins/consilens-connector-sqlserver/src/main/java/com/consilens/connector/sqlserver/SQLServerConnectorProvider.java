package com.consilens.connector.sqlserver;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class SQLServerConnectorProvider extends AbstractJdbcConnectorProvider {

    public SQLServerConnectorProvider() {
        super("sqlserver", DatabaseType.SQL_SERVER);
    }
}
