package com.consilens.connector.api.spi;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;

public interface ConnectorProvider {

    String getType();

    ConnectorAdapter create(ConnectorConfig config) throws ConnectorException;
}
