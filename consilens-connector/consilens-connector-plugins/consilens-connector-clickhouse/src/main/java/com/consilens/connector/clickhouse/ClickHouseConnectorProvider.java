package com.consilens.connector.clickhouse;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class ClickHouseConnectorProvider extends AbstractJdbcConnectorProvider {

    public ClickHouseConnectorProvider() {
        super("clickhouse", ClickHouseDatabaseDialect::new);
    }
}
