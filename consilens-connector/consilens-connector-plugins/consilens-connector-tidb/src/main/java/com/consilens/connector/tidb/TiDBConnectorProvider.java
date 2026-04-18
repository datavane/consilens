package com.consilens.connector.tidb;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class TiDBConnectorProvider extends AbstractJdbcConnectorProvider {

    public TiDBConnectorProvider() {
        super("tidb", DatabaseType.TIDB, TiDBDatabaseDialect::new);
    }
}
