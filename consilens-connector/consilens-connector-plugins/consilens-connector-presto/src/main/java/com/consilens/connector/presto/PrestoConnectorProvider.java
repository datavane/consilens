package com.consilens.connector.presto;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PrestoConnectorProvider extends AbstractJdbcConnectorProvider {

    public PrestoConnectorProvider() {
        super("presto", PrestoDatabaseDialect::new);
    }
}
