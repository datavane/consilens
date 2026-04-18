package com.consilens.connector.doris;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class DorisConnectorProvider extends AbstractJdbcConnectorProvider {

    public DorisConnectorProvider() {
        super("doris", DatabaseType.DORIS, DorisDatabaseDialect::new);
    }
}
