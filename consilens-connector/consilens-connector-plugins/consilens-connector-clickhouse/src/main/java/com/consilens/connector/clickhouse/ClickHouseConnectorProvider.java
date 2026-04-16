package com.consilens.connector.clickhouse;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class ClickHouseConnectorProvider extends AbstractJdbcConnectorProvider {

    public ClickHouseConnectorProvider() {
        super("clickhouse", DatabaseType.CLICKHOUSE);
    }
}
