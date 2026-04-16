package com.consilens.connector.starrocks;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class StarRocksConnectorProvider extends AbstractJdbcConnectorProvider {

    public StarRocksConnectorProvider() {
        super("starrocks", DatabaseType.STARROCKS);
    }
}
