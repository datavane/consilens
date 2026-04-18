package com.consilens.connector.mysql;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class MySQLConnectorProvider extends AbstractJdbcConnectorProvider {

    public MySQLConnectorProvider() {
        super("mysql", DatabaseType.MYSQL, MySQLDatabaseDialect::new);
    }
}
