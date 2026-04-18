package com.consilens.conncetor.base;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;
import com.consilens.conncetor.base.jdbc.JdbcConnectorAdapter;

import java.util.Map;
import java.util.function.Function;

public abstract class AbstractJdbcConnectorProvider implements ConnectorProvider {

    private final String type;
    private final DatabaseType databaseType;
    private final Function<Map<String, ?>, DatabaseDialect> dialectFactory;

    protected AbstractJdbcConnectorProvider(String type,
                                            DatabaseType databaseType,
                                            Function<Map<String, ?>, DatabaseDialect> dialectFactory) {
        this.type = type;
        this.databaseType = databaseType;
        this.dialectFactory = dialectFactory;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public ConnectorAdapter create(ConnectorConfig config) throws ConnectorException {
        return new JdbcConnectorAdapter(config, databaseType, dialectFactory);
    }
}
