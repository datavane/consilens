package com.consilens.connector.trino;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class TrinoConnectorProvider extends AbstractJdbcConnectorProvider {

    public TrinoConnectorProvider() {
        super("trino", DatabaseType.TRINO);
    }
}
