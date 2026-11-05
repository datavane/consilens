package com.consilens.connector.tidb;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class TiDBConnectorProvider extends AbstractJdbcConnectorProvider {

    public TiDBConnectorProvider() {
        super(ConnectorTypes.TIDB, TiDBDatabaseDialect::new);
    }
}
