package com.consilens.connector.oracle;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class OracleConnectorProvider extends AbstractJdbcConnectorProvider {

    public OracleConnectorProvider() {
        super("oracle", OracleDatabaseDialect::new);
    }
}
