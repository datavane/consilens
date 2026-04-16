package com.consilens.connector.oracle;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class OracleConnectorProvider extends AbstractJdbcConnectorProvider {

    public OracleConnectorProvider() {
        super("oracle", DatabaseType.ORACLE);
    }
}
