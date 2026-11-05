package com.consilens.connector.iceberg;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;

/**
 * Iceberg table source. Reads a local metadata JSON (or the latest metadata in
 * a table directory), resolves the current/selected snapshot's Parquet files
 * and delegates scanning to the file connector.
 */
public class IcebergConnectorProvider implements ConnectorProvider {

    public static final String TYPE = "iceberg";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ConnectorAdapter create(ConnectorConfig config) throws ConnectorException {
        return new IcebergConnectorAdapter(config);
    }

    private static final class IcebergConnectorAdapter implements ConnectorAdapter {

        private final ConnectorConfig config;

        private IcebergConnectorAdapter(ConnectorConfig config) {
            this.config = config;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getName() {
            return config != null && config.getName() != null ? config.getName() : TYPE;
        }

        @Override
        public DatasetHandle openDataset(ResourceLocator resource, ReadOptions readOptions) {
            return new IcebergDatasetHandle(resource != null ? resource
                    : config != null ? config.getResource() : null, readOptions);
        }

        @Override
        public void close() {
            // no shared resources
        }
    }
}
