package com.consilens.connector.api.dataset;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.ResourceLocator;

import java.util.Optional;

public interface DatasetHandle extends AutoCloseable {

    ResourceLocator getResource();

    DatasetMetadata getMetadata();

    SchemaDescriptor getSchema() throws ConnectorException;

    Optional<RecordScanner> getRecordScanner();

    Optional<SplitPlanner> getSplitPlanner();

    Optional<HashProvider> getHashProvider();

    Optional<KeyLookupProvider> getKeyLookupProvider();

    Optional<SnapshotProvider> getSnapshotProvider();

    Optional<FilterPushdownProvider> getFilterPushdownProvider();

    @Override
    void close() throws ConnectorException;
}
