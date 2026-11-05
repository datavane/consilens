package com.consilens.connector.oracle;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class OracleConnectorProvider extends AbstractJdbcConnectorProvider {

    public OracleConnectorProvider() {
        super(ConnectorTypes.ORACLE, OracleDatabaseDialect::new);
    }
}
