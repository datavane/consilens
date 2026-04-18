package com.consilens.connector.presto;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class PrestoConnectorProvider extends AbstractJdbcConnectorProvider {

    public PrestoConnectorProvider() {
        super("presto", DatabaseType.PRESTO, PrestoDatabaseDialect::new);
    }
}
