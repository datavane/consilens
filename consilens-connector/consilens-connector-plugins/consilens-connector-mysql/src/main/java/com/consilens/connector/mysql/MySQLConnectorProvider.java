package com.consilens.connector.mysql;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class MySQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public MySQLConnectorProvider() {
        super(ConnectorTypes.MYSQL, MySQLDatabaseDialect::new);
    }
}
