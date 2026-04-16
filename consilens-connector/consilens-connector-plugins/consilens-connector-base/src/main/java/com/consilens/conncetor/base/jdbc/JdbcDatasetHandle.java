package com.consilens.conncetor.base.jdbc;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.capability.CapabilitySet;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.PushdownResult;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class JdbcDatasetHandle implements DatasetHandle {

    private final ResourceLocator resource;
    private final DatasetMetadata metadata;
    private final FilterPushdownProvider filterPushdownProvider;

    public JdbcDatasetHandle(String connectorName,
                             DatabaseType databaseType,
                             Map<String, Object> connection,
                             ResourceLocator resource,
                             ReadOptions readOptions) {
        this.resource = resource;
        this.filterPushdownProvider = predicate -> PushdownResult.builder()
                .pushedPredicate(predicate)
                .residualPredicate(null)
                .build();
        this.metadata = createMetadata(connectorName, databaseType, connection, resource, readOptions);
    }

    @Override
    public ResourceLocator getResource() {
        return resource;
    }

    @Override
    public DatasetMetadata getMetadata() {
        return metadata;
    }

    @Override
    public SchemaDescriptor getSchema() throws ConnectorException {
        throw new ConnectorException("JDBC schema discovery is provided by the core JDBC bridge at execution time");
    }

    @Override
    public Optional<RecordScanner> getRecordScanner() {
        return Optional.empty();
    }

    @Override
    public Optional<SplitPlanner> getSplitPlanner() {
        return Optional.empty();
    }

    @Override
    public Optional<HashProvider> getHashProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<KeyLookupProvider> getKeyLookupProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<SnapshotProvider> getSnapshotProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<FilterPushdownProvider> getFilterPushdownProvider() {
        return Optional.of(filterPushdownProvider);
    }

    @Override
    public void close() throws ConnectorException {
        // Dataset handle does not own runtime resources in plugin layer.
    }

    private DatasetMetadata createMetadata(String connectorName,
                                           DatabaseType databaseType,
                                           Map<String, Object> connection,
                                           ResourceLocator resource,
                                           ReadOptions readOptions) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connectorName", connectorName);
        attributes.put("databaseType", databaseType.name());
        attributes.put("connection", connection != null ? new LinkedHashMap<>(connection) : Map.of());
        attributes.put("readOptions", readOptions);
        attributes.put("resourceType", resource != null ? resource.getType() : null);

        return DatasetMetadata.builder()
                .logicalName(resource != null ? (resource.getName() != null ? resource.getName() : resource.getPath()) : null)
                .executionDomainId(buildExecutionDomainId(databaseType, connection))
                .capabilities(new CapabilitySet(EnumSet.of(
                        ConnectorCapability.FILTER_PUSHDOWN,
                        ConnectorCapability.PROJECTION_PUSHDOWN,
                        ConnectorCapability.SERVER_SIDE_COUNT,
                        ConnectorCapability.SERVER_SIDE_HASH,
                        ConnectorCapability.RANGE_SPLIT,
                        ConnectorCapability.SERVER_SIDE_JOIN
                )))
                .attributes(attributes)
                .build();
    }

    private String buildExecutionDomainId(DatabaseType databaseType, Map<String, Object> connection) {
        Object jdbcUrl = connection != null ? connection.get("url") : null;
        return databaseType.name() + ":" + String.valueOf(jdbcUrl);
    }
}
