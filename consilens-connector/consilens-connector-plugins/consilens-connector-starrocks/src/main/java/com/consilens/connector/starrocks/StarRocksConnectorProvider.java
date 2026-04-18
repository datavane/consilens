package com.consilens.connector.starrocks;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class StarRocksConnectorProvider extends AbstractJdbcConnectorProvider {

    public StarRocksConnectorProvider() {
        super("starrocks", StarRocksDatabaseDialect::new);
    }
}
