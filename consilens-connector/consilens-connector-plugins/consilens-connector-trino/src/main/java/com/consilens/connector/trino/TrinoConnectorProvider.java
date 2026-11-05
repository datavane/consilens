package com.consilens.connector.trino;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class TrinoConnectorProvider extends AbstractJdbcConnectorProvider {

    public TrinoConnectorProvider() {
        super(ConnectorTypes.TRINO, TrinoDatabaseDialect::new);
    }
}
