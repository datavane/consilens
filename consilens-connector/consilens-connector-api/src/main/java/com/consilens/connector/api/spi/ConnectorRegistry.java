package com.consilens.connector.api.spi;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;

import java.util.Optional;

public interface ConnectorRegistry {

    ConnectorAdapter create(ConnectorConfig config) throws ConnectorException;

    Optional<ConnectorProvider> findProvider(String type);
}
