package com.consilens.core.compare.jdbc.support;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.normalization.DefaultNormalizationSpecValidator;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.compare.jdbc.normalization.LegacyNormalizationAdapter;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.adpter.DefaultDatabaseAdapter;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.database.connection.ConnectionPoolFactory;
import com.consilens.core.database.dialect.DialectFactory;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JdbcLegacyBridgeFactory {

    private JdbcLegacyBridgeFactory() {
    }

    public static JdbcLegacySegment openSegment(CompareSegment segment) {
        if (segment == null || segment.getDataset() == null || segment.getDataset().getMetadata() == null) {
            throw new ConnectorException("CompareSegment dataset metadata is required for JDBC bridge");
        }

        Map<String, Object> attributes = segment.getDataset().getMetadata().getAttributes();
        if (attributes == null || attributes.isEmpty()) {
            throw new ConnectorException("Dataset metadata attributes are required for JDBC bridge");
        }

        DatabaseType databaseType = resolveDatabaseType(attributes.get("databaseType"));
        String connectorName = stringValue(attributes.get("connectorName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = attributes.get("connection") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) attributes.get("connection"))
                : Map.of();
        ReadOptions readOptions = attributes.get("readOptions") instanceof ReadOptions
                ? (ReadOptions) attributes.get("readOptions")
                : null;

        ResourceLocator resource = segment.getResource() != null ? segment.getResource() : segment.getDataset().getResource();
        TablePath tablePath = resolveTablePath(resource);
        DatabaseAdapter databaseAdapter = createDatabaseAdapter(connectorName, databaseType, connection, readOptions);
        SchemaDescriptor schema = segment.getSchema() != null
                ? segment.getSchema()
                : JdbcSchemaAdapter.adapt(databaseAdapter.getTableSchema(tablePath.getComponents()));

        return new JdbcLegacySegment(databaseAdapter, tablePath, schema);
    }

    private static DatabaseAdapter createDatabaseAdapter(String connectorName,
                                                         DatabaseType databaseType,
                                                         Map<String, Object> connection,
                                                         ReadOptions readOptions) {
        String url = stringValue(connection.get("url"));
        String username = stringValue(connection.get("username"));
        String password = stringValue(connection.get("password"));

        if (url == null || url.trim().isEmpty()) {
            throw new ConnectorException("JDBC bridge requires connection.url");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new ConnectorException("JDBC bridge requires connection.username");
        }

        Map<String, Object> readOptionsMap = readOptions != null ? readOptions.getOptions() : null;
        NormalizationSpec normalizationSpec = LegacyNormalizationAdapter.extractNormalizationSpec(
                readOptionsMap != null ? readOptionsMap.get("normalization") : null);
        new DefaultNormalizationSpecValidator().validate(normalizationSpec);
        String normalizationSide = stringValue(readOptionsMap != null ? readOptionsMap.get("normalizationSide") : null);
        Map<String, ?> legacyNormalization = LegacyNormalizationAdapter.toLegacyRuleMap(
                normalizationSpec,
                normalizationSide != null ? normalizationSide : "source");

        PoolConfiguration poolConfiguration = ConnectionPoolFactory.getDefaultConfiguration(databaseType);
        ConnectionPool connectionPool = ConnectionPoolFactory.createPool(url, username, password, databaseType, poolConfiguration);
        DatabaseDialect dialect = DialectFactory.getDialect(databaseType, legacyNormalization);
        return new DefaultDatabaseAdapter(
                connectorName != null ? connectorName : databaseType.name().toLowerCase(),
                connectionPool,
                dialect,
                url,
                ChecksumAlgorithm.CONCAT);
    }

    private static DatabaseType resolveDatabaseType(Object value) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ConnectorException("Dataset metadata is missing databaseType for JDBC bridge");
        }
        return DatabaseType.valueOf(((String) value).trim().toUpperCase());
    }

    private static TablePath resolveTablePath(ResourceLocator resource) {
        if (resource == null) {
            throw new ConnectorException("ResourceLocator cannot be null");
        }
        if (resource.getName() != null && !resource.getName().trim().isEmpty()) {
            return TablePath.fromString(resource.getName().trim());
        }
        if (resource.getPath() != null && !resource.getPath().trim().isEmpty()) {
            return TablePath.fromString(resource.getPath().trim());
        }
        throw new ConnectorException("JDBC resource requires resource.name or resource.path");
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    @Getter
    public static final class JdbcLegacySegment implements AutoCloseable {

        private final DatabaseAdapter databaseAdapter;
        private final TablePath tablePath;
        private final SchemaDescriptor schema;

        private JdbcLegacySegment(DatabaseAdapter databaseAdapter, TablePath tablePath, SchemaDescriptor schema) {
            this.databaseAdapter = databaseAdapter;
            this.tablePath = tablePath;
            this.schema = schema;
        }

        @Override
        public void close() {
            if (databaseAdapter != null) {
                databaseAdapter.close();
            }
        }
    }
}
