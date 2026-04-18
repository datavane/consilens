package com.consilens.connector.doris;

import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class DorisConnectorProvider extends AbstractJdbcConnectorProvider {

    public DorisConnectorProvider() {
        super("doris", DorisDatabaseDialect::new);
    }
}
