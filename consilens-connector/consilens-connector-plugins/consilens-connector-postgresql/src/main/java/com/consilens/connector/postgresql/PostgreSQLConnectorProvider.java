package com.consilens.connector.postgresql;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PostgreSQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public PostgreSQLConnectorProvider() {
        super(ConnectorTypes.POSTGRESQL, PostgreSQLDatabaseDialect::new);
    }
}
