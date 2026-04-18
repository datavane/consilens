package com.consilens.connector.tidb;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class TiDBConnectorProvider extends AbstractJdbcConnectorProvider {

    public TiDBConnectorProvider() {
        super("tidb", TiDBDatabaseDialect::new);
    }
}
