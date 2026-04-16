package com.consilens.conncetor.base;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;
import com.consilens.conncetor.base.jdbc.JdbcConnectorAdapter;

public abstract class AbstractJdbcConnectorProvider implements ConnectorProvider {

    private final String type;
    private final DatabaseType databaseType;

    protected AbstractJdbcConnectorProvider(String type, DatabaseType databaseType) {
        this.type = type;
        this.databaseType = databaseType;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public ConnectorAdapter create(ConnectorConfig config) throws ConnectorException {
        return new JdbcConnectorAdapter(config, databaseType);
    }

    @Override
    public boolean supports(ConnectorConfig config) {
        if (ConnectorProvider.super.supports(config)) {
            return true;
        }
        if (config == null || config.getConnection() == null) {
            return false;
        }
        Object url = config.getConnection().get("url");
        if (!(url instanceof String) || ((String) url).trim().isEmpty()) {
            return false;
        }
        DatabaseType detectedType = DatabaseType.fromJdbcUrl((String) url);
        return databaseType == detectedType;
    }
}
