package com.consilens.connector.doris;

import com.consilens.connector.api.ConnectorTypes;
import com.consilens.conncetor.base.AbstractJdbcConnectorProvider;

public class DorisConnectorProvider extends AbstractJdbcConnectorProvider {

    public DorisConnectorProvider() {
        super(ConnectorTypes.DORIS, DorisDatabaseDialect::new);
    }
}
