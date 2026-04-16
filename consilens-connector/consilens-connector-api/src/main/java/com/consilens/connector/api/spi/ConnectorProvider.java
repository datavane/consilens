package com.consilens.connector.api.spi;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;

public interface ConnectorProvider {

    String getType();

    ConnectorAdapter create(ConnectorConfig config) throws ConnectorException;

    default boolean supports(ConnectorConfig config) {
        if (config == null || config.getType() == null || config.getType().trim().isEmpty()) {
            return false;
        }
        return getType().equalsIgnoreCase(config.getType().trim());
    }
}
