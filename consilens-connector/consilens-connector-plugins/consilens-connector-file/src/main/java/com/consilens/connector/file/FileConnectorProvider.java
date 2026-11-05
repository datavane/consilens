package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;

/**
 * Connector for local CSV/JSONL files. It exposes a RecordScanner and is
 * consumed by the existing StreamingMergePlan; no pushdown/join capabilities
 * are claimed.
 */
public class FileConnectorProvider implements ConnectorProvider {

    public static final String TYPE = "file";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ConnectorAdapter create(ConnectorConfig config) throws ConnectorException {
        return new FileConnectorAdapter(config);
    }

    private static final class FileConnectorAdapter implements ConnectorAdapter {

        private final ConnectorConfig config;

        private FileConnectorAdapter(ConnectorConfig config) {
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
            return new FileDatasetHandle(resource != null ? resource
                    : config != null ? config.getResource() : null, readOptions);
        }

        @Override
        public void close() {
            // no resources
        }
    }
}
