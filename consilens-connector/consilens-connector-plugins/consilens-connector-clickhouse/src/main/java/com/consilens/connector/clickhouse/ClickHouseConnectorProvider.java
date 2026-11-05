package com.consilens.connector.clickhouse;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class ClickHouseConnectorProvider extends AbstractJdbcConnectorProvider {

    public ClickHouseConnectorProvider() {
        super(ConnectorTypes.CLICKHOUSE, ClickHouseDatabaseDialect::new);
    }
}
