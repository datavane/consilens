package com.consilens.connector.postgresql;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PostgreSQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public PostgreSQLConnectorProvider() {
        super("postgresql", DatabaseType.POSTGRESQL);
    }
}
