package com.consilens.connector.presto;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PrestoConnectorProvider extends AbstractJdbcConnectorProvider {

    public PrestoConnectorProvider() {
        super(ConnectorTypes.PRESTO, PrestoDatabaseDialect::new);
    }
}
