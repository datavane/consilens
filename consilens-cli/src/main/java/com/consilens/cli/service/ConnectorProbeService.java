package com.consilens.cli.service;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.dataset.HashOptions;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorRegistry;
import com.consilens.core.compare.registry.DefaultConnectorRegistry;

public final class ConnectorProbeService {

    private final ConnectorRegistry connectorRegistry;

    public ConnectorProbeService() {
        this(new DefaultConnectorRegistry());
    }

    public ConnectorProbeService(ConnectorRegistry connectorRegistry) {
        this.connectorRegistry = connectorRegistry;
    }

    public void verifyAccessible(ConnectorConfig config) throws Exception {
        try (ConnectorAdapter adapter = connectorRegistry.create(config);
             com.consilens.connector.api.dataset.DatasetHandle dataset = adapter.openDataset(config.getResource(), config.getReadOptions())) {
            dataset.getSchema();
        }
    }

    public long countRows(ConnectorConfig config,
                          KeySpec keySpec,
                          ComparisonSpec comparisons,
                          PredicateSpec filter,
                          String side) throws Exception {
        try (ConnectorAdapter adapter = connectorRegistry.create(config);
             com.consilens.connector.api.dataset.DatasetHandle dataset = adapter.openDataset(config.getResource(), config.getReadOptions())) {
            SchemaDescriptor schema = dataset.getSchema();
            CompareSegment segment = CompareSegment.builder()
                    .dataset(dataset)
                    .resource(config.getResource())
                    .keySpec(keySpec)
                    .comparisons(comparisons)
                    .filter(filter)
                    .schema(schema)
                    .side(side)
                    .build();

            if (dataset.getHashProvider().isPresent()) {
                Long rowCount = dataset.getHashProvider().get()
                        .digest(segment, HashOptions.builder().algorithm("concat").build())
                        .getRowCount();
                return rowCount != null ? rowCount : 0L;
            }

            RecordScanner scanner = dataset.getRecordScanner()
                    .orElseThrow(() -> new IllegalStateException("RecordScanner is required to count rows"));
            long count = 0L;
            try (CloseableIterator<com.consilens.connector.api.record.CanonicalRecord> iterator = scanner.scan(segment)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    count++;
                }
            }
            return count;
        }
    }
}
