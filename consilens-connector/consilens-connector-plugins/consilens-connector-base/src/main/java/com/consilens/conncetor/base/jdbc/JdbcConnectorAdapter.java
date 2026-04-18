package com.consilens.conncetor.base.jdbc;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.spi.ConnectorAdapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public class JdbcConnectorAdapter implements ConnectorAdapter {

    private final ConnectorConfig config;
    private final String connectorType;
    private final Function<Map<String, ?>, DatabaseDialect> dialectFactory;

    public JdbcConnectorAdapter(ConnectorConfig config,
                                String connectorType,
                                Function<Map<String, ?>, DatabaseDialect> dialectFactory) {
        this.config = config;
        this.connectorType = connectorType;
        this.dialectFactory = dialectFactory;
    }

    @Override
    public String getType() {
        return config.getType();
    }

    @Override
    public String getName() {
        return config.getName() != null ? config.getName() : connectorType;
    }

    @Override
    public DatasetHandle openDataset(ResourceLocator resource, ReadOptions readOptions) throws ConnectorException {
        if (resource == null) {
            throw new ConnectorException("ResourceLocator cannot be null for JDBC connector");
        }
        return new JdbcDatasetHandle(
                getName(),
                connectorType,
                dialectFactory,
                config.getConnection() != null ? new LinkedHashMap<>(config.getConnection()) : Map.of(),
                resource,
                readOptions);
    }

    @Override
    public void close() throws ConnectorException {
        // Connector adapter in plugin layer does not own core runtime resources.
    }
}
