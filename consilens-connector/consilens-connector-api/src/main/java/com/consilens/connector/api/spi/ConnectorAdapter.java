package com.consilens.connector.api.spi;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ResourceLocator;

public interface ConnectorAdapter extends AutoCloseable {

    String getType();

    String getName();

    DatasetHandle openDataset(ResourceLocator resource, ReadOptions readOptions) throws ConnectorException;

    @Override
    void close() throws ConnectorException;
}
