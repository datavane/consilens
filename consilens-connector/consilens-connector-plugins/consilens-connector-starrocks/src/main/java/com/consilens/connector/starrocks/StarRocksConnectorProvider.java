package com.consilens.connector.starrocks;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class StarRocksConnectorProvider extends AbstractJdbcConnectorProvider {

    public StarRocksConnectorProvider() {
        super(ConnectorTypes.STARROCKS, StarRocksDatabaseDialect::new);
    }
}
