package com.consilens.connector.oceanbase;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

/**
 * OceanBase connector provider.
 *
 * <p>
 * Registers OceanBase as a JDBC connector with type "oceanbase".
 * OceanBase is highly compatible with MySQL syntax in MySQL mode,
 * so this provider leverages the MySQL-compatible JDBC driver.
 * </p>
 *
 * @since 1.0.0
 */
public class OceanBaseConnectorProvider extends AbstractJdbcConnectorProvider {

    public OceanBaseConnectorProvider() {
        super(ConnectorTypes.OCEANBASE, OceanBaseDatabaseDialect::new);
    }
}
