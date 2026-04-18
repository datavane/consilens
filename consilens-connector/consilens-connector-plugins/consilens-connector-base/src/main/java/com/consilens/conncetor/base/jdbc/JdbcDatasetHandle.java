package com.consilens.conncetor.base.jdbc;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
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
import com.consilens.connector.api.dataset.HashOptions;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.dataset.SegmentDigest;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.planner.KeyRangeSplit;
import com.consilens.connector.api.planner.OffsetLimitSplit;
import com.consilens.connector.api.planner.SegmentSplit;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.record.RecordKey;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

@Slf4j
public class JdbcDatasetHandle implements DatasetHandle, RelationalDatasetSupport {

    private static final int DEFAULT_STREAM_FETCH_SIZE = 1000;

    private final String connectorName;
    private final ResourceLocator resource;
    private final DatasetMetadata metadata;
    private final DatabaseType databaseType;
    private final Map<String, Object> connection;
    private final ReadOptions readOptions;
    private final DatabaseDialect dialect;
    private final RecordScanner recordScanner;
    private final HashProvider hashProvider;
    private final FilterPushdownProvider filterPushdownProvider;
    private volatile HikariDataSource dataSource;
    private volatile SchemaDescriptor schema;
    private volatile boolean closed;

    public JdbcDatasetHandle(String connectorName,
                             DatabaseType databaseType,
                             Function<Map<String, ?>, DatabaseDialect> dialectFactory,
                             Map<String, Object> connection,
                             ResourceLocator resource,
                             ReadOptions readOptions) {
        this.resource = resource;
        this.connectorName = connectorName;
        this.databaseType = databaseType;
        this.connection = connection != null ? new LinkedHashMap<>(connection) : Map.of();
        this.readOptions = readOptions;
        this.dialect = dialectFactory.apply(toLegacyNormalization(readOptions));
        this.recordScanner = this::scanRecords;
        this.hashProvider = this::digestSegment;
        this.filterPushdownProvider = predicate -> PushdownResult.builder()
                .pushedPredicate(predicate)
                .residualPredicate(null)
                .build();
        this.metadata = createMetadata(connectorName, databaseType, this.connection, resource, readOptions);
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
        if (schema == null) {
            schema = discoverSchema();
        }
        return schema;
    }

    @Override
    public Optional<RecordScanner> getRecordScanner() {
        return Optional.of(recordScanner);
    }

    @Override
    public Optional<SplitPlanner> getSplitPlanner() {
        return Optional.empty();
    }

    @Override
    public Optional<HashProvider> getHashProvider() {
        return Optional.of(hashProvider);
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
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            HikariDataSource current = dataSource;
            dataSource = null;
            if (current != null) {
                current.close();
            }
        }
    }

    @Override
    public String getName() {
        return connectorName;
    }

    @Override
    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    @Override
    public String getJdbcUrl() {
        return requireJdbcUrl();
    }

    @Override
    public String getUsername() {
        return firstString(connection.get("username"), connection.get("user"));
    }

    @Override
    public DatabaseDialect getDialect() {
        return dialect;
    }

    @Override
    public TablePath getTablePath() {
        return resolveTablePath(resource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getOrCreateDataSource().getConnection();
    }

    private DatasetMetadata createMetadata(String connectorName,
                                           DatabaseType databaseType,
                                           Map<String, Object> connection,
                                           ResourceLocator resource,
                                           ReadOptions readOptions) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("databaseType", databaseType.name());
        attributes.put("resourceType", resource != null ? resource.getType() : null);
        attributes.put("connectorName", connectorName);

        return DatasetMetadata.builder()
                .logicalName(resource != null ? (resource.getName() != null ? resource.getName() : resource.getPath()) : null)
                .executionDomainId(buildExecutionDomainId(databaseType, connection))
                .capabilities(new CapabilitySet(EnumSet.of(
                        ConnectorCapability.SCHEMA_DISCOVERY,
                        ConnectorCapability.FILTER_PUSHDOWN,
                        ConnectorCapability.PROJECTION_PUSHDOWN,
                        ConnectorCapability.SERVER_SIDE_JOIN,
                        ConnectorCapability.SERVER_SIDE_HASH,
                        ConnectorCapability.ORDERED_SCAN,
                        ConnectorCapability.STREAM_SCAN
                )))
                .attributes(attributes)
                .build();
    }

    private String buildExecutionDomainId(DatabaseType databaseType, Map<String, Object> connection) {
        Object jdbcUrl = connection != null ? connection.get("url") : null;
        return databaseType.name() + ":" + String.valueOf(jdbcUrl);
    }

    private HikariDataSource getOrCreateDataSource() {
        if (closed) {
            throw new ConnectorException("JdbcDatasetHandle is already closed");
        }
        HikariDataSource current = dataSource;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (closed) {
                throw new ConnectorException("JdbcDatasetHandle is already closed");
            }
            if (dataSource == null) {
                dataSource = new HikariDataSource(buildDataSourceConfig());
            }
            return dataSource;
        }
    }

    private HikariConfig buildDataSourceConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(requireJdbcUrl());
        String username = getUsername();
        if (username != null && !username.trim().isEmpty()) {
            config.setUsername(username);
        }
        String password = stringValue(connection.get("password"));
        if (password != null) {
            config.setPassword(password);
        }
        String driverClassName = firstString(connection.get("driverClassName"), connection.get("driver"));
        if (driverClassName != null && !driverClassName.trim().isEmpty()) {
            config.setDriverClassName(driverClassName.trim());
        }

        Properties properties = buildConnectionProperties(
                connection,
                dialect.getConnectionPoolOptimizer().getOptimizationProperties(resolveUseSsl()));
        properties.remove("user");
        properties.remove("password");
        for (String propertyName : properties.stringPropertyNames()) {
            config.addDataSourceProperty(propertyName, properties.getProperty(propertyName));
        }
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(8);
        config.setPoolName("connector-" + connectorName + "-" + Integer.toHexString(System.identityHashCode(this)));
        return config;
    }

    private String requireJdbcUrl() {
        Object rawUrl = connection.get("url");
        if (!(rawUrl instanceof String) || ((String) rawUrl).trim().isEmpty()) {
            throw new ConnectorException("JDBC connector requires connection.url");
        }
        return ((String) rawUrl).trim();
    }

    private SchemaDescriptor discoverSchema() {
        TablePath tablePath = resolveTablePath(resource);
        log.debug("Discovering JDBC schema for {}", tablePath.getFullPath());
        try (Connection jdbcConnection = openConnection();
             ResultSet columns = metadataColumns(jdbcConnection, tablePath)) {
            List<FieldDescriptor> fields = new ArrayList<>();
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");
                boolean nullable = DatabaseMetaData.columnNullable == columns.getInt("NULLABLE");
                int ordinal = columns.getInt("ORDINAL_POSITION");
                DataType dataType = dialect.getDataTypeHandler().convertToDataType(typeName);
                fields.add(FieldDescriptor.builder()
                        .name(columnName)
                        .canonicalType(dataType.name().toLowerCase(Locale.ROOT))
                        .nullable(nullable)
                        .ordinal(ordinal)
                        .attributes(Map.of("sourceType", typeName))
                        .build());
            }

            if (fields.isEmpty()) {
                return discoverSchemaFromResultSet(jdbcConnection, tablePath);
            }
            Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
            for (FieldDescriptor field : fields) {
                fieldMap.put(field.getName(), field);
            }
            return SchemaDescriptor.builder()
                    .fields(List.copyOf(fields))
                    .fieldMap(fieldMap)
                    .build();
        } catch (SQLException e) {
            throw new ConnectorException("Failed to discover JDBC schema for " + tablePath.getFullPath(), e);
        }
    }

    private ResultSet metadataColumns(Connection jdbcConnection, TablePath tablePath) throws SQLException {
        DatabaseMetaData databaseMetaData = jdbcConnection.getMetaData();
        String catalog = tablePath.getCatalog().orElse(null);
        String schemaName = tablePath.getSchema().orElse(null);
        return databaseMetaData.getColumns(catalog, schemaName, tablePath.getTableName(), null);
    }

    private SchemaDescriptor discoverSchemaFromResultSet(Connection jdbcConnection, TablePath tablePath) throws SQLException {
        String sql = dialect.getSqlQueryGenerator().getSelectSQL(
                tablePath.getSchema().orElse(null),
                tablePath.getTableName(),
                List.of("*"),
                "1 = 0",
                null);
        try (PreparedStatement statement = jdbcConnection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
            List<FieldDescriptor> fields = new ArrayList<>();
            Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
            for (int index = 1; index <= resultSetMetaData.getColumnCount(); index++) {
                String columnName = resultSetMetaData.getColumnLabel(index);
                String typeName = resultSetMetaData.getColumnTypeName(index);
                DataType dataType = dialect.getDataTypeHandler().convertToDataType(typeName);
                FieldDescriptor field = FieldDescriptor.builder()
                        .name(columnName)
                        .canonicalType(dataType.name().toLowerCase(Locale.ROOT))
                        .nullable(resultSetMetaData.isNullable(index) != ResultSetMetaData.columnNoNulls)
                        .ordinal(index)
                        .attributes(Map.of("sourceType", typeName))
                        .build();
                fields.add(field);
                fieldMap.put(columnName, field);
            }
            return SchemaDescriptor.builder()
                    .fields(List.copyOf(fields))
                    .fieldMap(fieldMap)
                    .build();
        }
    }

    private CloseableIterator<CanonicalRecord> scanRecords(CompareSegment segment) {
        SchemaDescriptor schemaDescriptor = segment.getSchema() != null ? segment.getSchema() : getSchema();
        List<String> selectColumns = selectColumns(segment, schemaDescriptor);
        TablePath tablePath = resolveTablePath(resource);
        String sql = buildScanSql(segment, schemaDescriptor, selectColumns, tablePath);
        Connection jdbcConnection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            jdbcConnection = openConnection();
            configureStreamingConnection(jdbcConnection);
            statement = jdbcConnection.prepareStatement(sql);
            applyReadOptions(statement);
            resultSet = statement.executeQuery();
            log.debug("Started JDBC ordered scan for {} with SQL: {}", tablePath.getFullPath(), sql);
            return new JdbcRecordIterator(jdbcConnection, statement, resultSet, selectColumns, segment, schemaDescriptor);
        } catch (SQLException e) {
            closeQuietly(resultSet);
            closeQuietly(statement);
            closeQuietly(jdbcConnection);
            throw new ConnectorException("Failed to scan JDBC dataset " + tablePath.getFullPath(), e);
        }
    }

    private SegmentDigest digestSegment(CompareSegment segment, HashOptions options) {
        SchemaDescriptor schemaDescriptor = segment.getSchema() != null ? segment.getSchema() : getSchema();
        List<String> keyColumns = keyColumns(segment);
        List<String> comparisonColumns = comparisonColumns(segment.getComparisons(), schemaDescriptor, keyColumns);
        List<String> checksumColumns = new ArrayList<>(keyColumns);
        checksumColumns.addAll(comparisonColumns);
        String whereClause = buildWhereClause(segment, keyColumns);
        TablePath tablePath = resolveTablePath(resource);
        String sql = dialect.getSqlQueryGenerator().getChecksumSQL(
                tablePath.getSchema().orElse(null),
                tablePath.getTableName(),
                keyColumns,
                checksumColumns,
                columnTypes(schemaDescriptor, checksumColumns),
                whereClause,
                resolveChecksumAlgorithm(options));
        long startTime = System.currentTimeMillis();
        log.info("Executing checksum pushdown for {}", tablePath.getFullPath());
        log.debug("Checksum SQL for {}: {}", tablePath.getFullPath(), sql);
        try (Connection jdbcConnection = openConnection();
             PreparedStatement statement = jdbcConnection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return SegmentDigest.builder().rowCount(0L).digest("").build();
            }
            SegmentDigest digest = SegmentDigest.builder()
                    .rowCount(readLong(resultSet, "row_count", 1))
                    .digest(readString(resultSet, "checksum", 2))
                    .build();
            log.info("Completed checksum pushdown for {} in {} ms (rows={})",
                    tablePath.getFullPath(),
                    System.currentTimeMillis() - startTime,
                    digest.getRowCount());
            return digest;
        } catch (SQLException e) {
            throw new ConnectorException("Failed to hash JDBC dataset " + tablePath.getFullPath(), e);
        }
    }

    private String buildScanSql(CompareSegment segment,
                                SchemaDescriptor schemaDescriptor,
                                List<String> selectColumns,
                                TablePath tablePath) {
        List<String> selectExpressions = new ArrayList<>();
        for (String column : selectColumns) {
            selectExpressions.add(dialect.getCapabilityProvider().quote(column));
        }
        List<String> keyColumns = keyColumns(segment);
        String sql = dialect.getSqlQueryGenerator().getSelectSQL(
                tablePath.getSchema().orElse(null),
                tablePath.getTableName(),
                selectExpressions,
                buildWhereClause(segment, keyColumns),
                keyColumns.isEmpty() ? null : keyColumns);
        if (segment.getSplit() instanceof OffsetLimitSplit) {
            OffsetLimitSplit split = (OffsetLimitSplit) segment.getSplit();
            sql = sql + " " + dialect.getSqlQueryGenerator().getLimitClause(split.getOffset(), split.getLimit());
        }
        return sql;
    }

    private CanonicalRecord readRecord(ResultSet resultSet,
                                       List<String> selectColumns,
                                       CompareSegment segment,
                                       SchemaDescriptor schemaDescriptor) throws SQLException {
        List<String> keyColumns = keyColumns(segment);
        List<Object> keyParts = new ArrayList<>(keyColumns.size());
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        Map<String, FieldDescriptor> fieldMap = schemaDescriptor.getFieldMap() != null
                ? schemaDescriptor.getFieldMap()
                : Collections.emptyMap();
        for (int index = 0; index < selectColumns.size(); index++) {
            String column = selectColumns.get(index);
            Object value = resultSet.getObject(index + 1);
            if (keyColumns.contains(column)) {
                keyParts.add(value);
            }
            FieldDescriptor field = fieldMap.get(column);
            values.put(column, CanonicalValue.builder()
                    .type(field != null ? field.getCanonicalType() : null)
                    .value(value)
                    .build());
        }
        return new JdbcCanonicalRecord(RecordKey.builder().parts(keyParts).build(), values);
    }

    private void applyReadOptions(PreparedStatement statement) throws SQLException {
        Integer fetchSize = readOptions != null ? readOptions.getFetchSize() : null;
        statement.setFetchSize(fetchSize != null && fetchSize > 0 ? fetchSize : DEFAULT_STREAM_FETCH_SIZE);
    }

    private Connection openConnection() throws SQLException {
        return getConnection();
    }

    static Properties buildConnectionProperties(Map<String, Object> connection, Properties baseProperties) {
        Properties properties = new Properties();
        if (baseProperties != null) {
            properties.putAll(baseProperties);
        }
        if (connection == null || connection.isEmpty()) {
            return properties;
        }
        for (Map.Entry<String, Object> entry : connection.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.isEmpty() || "url".equalsIgnoreCase(key)) {
                continue;
            }
            String normalizedKey = normalizeConnectionPropertyKey(key);
            if (normalizedKey != null) {
                properties.setProperty(normalizedKey, String.valueOf(entry.getValue()));
            }
        }
        return properties;
    }

    private static String normalizeConnectionPropertyKey(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "username":
                return "user";
            case "driver":
            case "driverclassname":
                return null;
            default:
                return key;
        }
    }

    private boolean resolveUseSsl() {
        Object configured = connection.get("useSSL");
        if (configured instanceof Boolean) {
            return (Boolean) configured;
        }
        if (configured instanceof String) {
            return Boolean.parseBoolean((String) configured);
        }
        return false;
    }

    private void configureStreamingConnection(Connection jdbcConnection) throws SQLException {
        if (databaseType == DatabaseType.POSTGRESQL && jdbcConnection.getAutoCommit()) {
            jdbcConnection.setAutoCommit(false);
        }
    }

    private List<String> selectColumns(CompareSegment segment, SchemaDescriptor schemaDescriptor) {
        List<String> keyColumns = keyColumns(segment);
        LinkedHashSet<String> columns = new LinkedHashSet<>(keyColumns);
        columns.addAll(comparisonColumns(segment.getComparisons(), schemaDescriptor, keyColumns));
        return List.copyOf(columns);
    }

    private List<String> comparisonColumns(ComparisonSpec comparisons,
                                           SchemaDescriptor schemaDescriptor,
                                           List<String> keyColumns) {
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            return List.copyOf(comparisons.getFields());
        }
        if (schemaDescriptor == null || schemaDescriptor.getFields() == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>(keyColumns);
        List<String> result = new ArrayList<>();
        for (FieldDescriptor field : schemaDescriptor.getFields()) {
            if (field.getName() != null && !keys.contains(field.getName())) {
                result.add(field.getName());
            }
        }
        return result;
    }

    private List<String> keyColumns(CompareSegment segment) {
        if (segment.getKeySpec() == null || segment.getKeySpec().getFields() == null) {
            return List.of();
        }
        return List.copyOf(segment.getKeySpec().getFields());
    }

    private Map<String, DataType> columnTypes(SchemaDescriptor schemaDescriptor, List<String> columns) {
        Map<String, DataType> result = new LinkedHashMap<>();
        Map<String, FieldDescriptor> fieldMap = schemaDescriptor.getFieldMap() != null
                ? schemaDescriptor.getFieldMap()
                : Collections.emptyMap();
        for (String column : columns) {
            FieldDescriptor field = fieldMap.get(column);
            result.put(column, field != null ? resolveDataType(field.getCanonicalType()) : DataType.UNKNOWN);
        }
        return result;
    }

    private DataType resolveDataType(String canonicalType) {
        if (canonicalType == null || canonicalType.trim().isEmpty()) {
            return DataType.UNKNOWN;
        }
        try {
            return DataType.valueOf(canonicalType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DataType.UNKNOWN;
        }
    }

    private String buildWhereClause(CompareSegment segment, List<String> keyColumns) {
        List<String> predicates = new ArrayList<>();
        if (segment.getFilter() != null
                && segment.getFilter().getExpression() != null
                && !segment.getFilter().getExpression().trim().isEmpty()) {
            predicates.add("(" + segment.getFilter().getExpression().trim() + ")");
        }
        SegmentSplit split = segment.getSplit();
        if (split instanceof KeyRangeSplit) {
            predicates.add(buildKeyRangePredicate((KeyRangeSplit) split, keyColumns));
        } else if (split != null && !(split instanceof OffsetLimitSplit)) {
            throw new ConnectorException("Unsupported JDBC split type: " + split.getClass().getSimpleName());
        }
        return predicates.isEmpty() ? null : String.join(" AND ", predicates);
    }

    private String buildKeyRangePredicate(KeyRangeSplit split, List<String> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new ConnectorException("Key range split requires key columns");
        }
        if (keyColumns.size() != 1) {
            throw new ConnectorException("Composite key range splits are not supported yet");
        }
        String column = dialect.getCapabilityProvider().quote(keyColumns.get(0));
        List<String> predicates = new ArrayList<>();
        if (split.getStartKey() != null && !split.getStartKey().isEmpty()) {
            predicates.add(column + " >= " + dialect.getSqlQueryGenerator().formatValue(split.getStartKey().get(0)));
        }
        if (split.getEndKey() != null && !split.getEndKey().isEmpty()) {
            predicates.add(column + " < " + dialect.getSqlQueryGenerator().formatValue(split.getEndKey().get(0)));
        }
        return predicates.isEmpty() ? "1 = 1" : String.join(" AND ", predicates);
    }

    private TablePath resolveTablePath(ResourceLocator locator) {
        if (locator == null) {
            throw new ConnectorException("ResourceLocator cannot be null");
        }
        if (locator.getName() != null && !locator.getName().trim().isEmpty()) {
            return TablePath.fromString(locator.getName().trim());
        }
        if (locator.getPath() != null && !locator.getPath().trim().isEmpty()) {
            return TablePath.fromString(locator.getPath().trim());
        }
        throw new ConnectorException("JDBC resource requires resource.name or resource.path");
    }

    private ChecksumAlgorithm resolveChecksumAlgorithm(HashOptions options) {
        return options != null
                ? ChecksumAlgorithm.fromString(options.getAlgorithm())
                : ChecksumAlgorithm.CONCAT;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup for failed scanner initialization.
        }
    }

    private Long readLong(ResultSet resultSet, String columnLabel, int fallbackIndex) throws SQLException {
        try {
            Number number = (Number) resultSet.getObject(columnLabel);
            return number != null ? number.longValue() : 0L;
        } catch (SQLException ignored) {
            Number number = (Number) resultSet.getObject(fallbackIndex);
            return number != null ? number.longValue() : 0L;
        }
    }

    private String readString(ResultSet resultSet, String columnLabel, int fallbackIndex) throws SQLException {
        try {
            return resultSet.getString(columnLabel);
        } catch (SQLException ignored) {
            return resultSet.getString(fallbackIndex);
        }
    }

    private Map<String, ?> toLegacyNormalization(ReadOptions options) {
        Map<String, Object> optionMap = options != null ? options.getOptions() : null;
        NormalizationSpec normalizationSpec = optionMap != null && optionMap.get("normalization") instanceof NormalizationSpec
                ? (NormalizationSpec) optionMap.get("normalization")
                : null;
        String side = optionMap != null && optionMap.get("normalizationSide") instanceof String
                ? (String) optionMap.get("normalizationSide")
                : null;
        if (normalizationSpec == null) {
            return null;
        }
        Map<String, JdbcTypeNormalizationRule> result = new LinkedHashMap<>();
        applyNormalizationRules(result, normalizationSpec.getGlobal());
        if ("source".equalsIgnoreCase(side)) {
            applyNormalizationRules(result, normalizationSpec.getSource());
        } else if ("target".equalsIgnoreCase(side)) {
            applyNormalizationRules(result, normalizationSpec.getTarget());
        }
        return result.isEmpty() ? null : result;
    }

    private void applyNormalizationRules(Map<String, JdbcTypeNormalizationRule> target, List<NormalizationRule> rules) {
        if (rules == null) {
            return;
        }
        for (NormalizationRule rule : rules) {
            if (rule == null || rule.getMatch() == null || rule.getMatch().getType() == null) {
                continue;
            }
            JdbcTypeNormalizationRule mapped = mapNormalizationRule(rule);
            if (mapped != null) {
                target.put(rule.getMatch().getType(), mapped);
            }
        }
    }

    private JdbcTypeNormalizationRule mapNormalizationRule(NormalizationRule rule) {
        Map<String, Object> params = rule.getParams() != null ? rule.getParams() : Map.of();
        JdbcTypeNormalizationRule mapped = new JdbcTypeNormalizationRule();
        String operation = rule.getOperation();
        if ("format_number".equals(operation)) {
            mapped.setPrecision(integerValue(params.get("precision")));
            mapped.setRounding(booleanValue(params.get("rounding")));
        } else if ("format_datetime".equals(operation)) {
            mapped.setFormat(stringValue(params.get("format")));
            mapped.setTimezone(stringValue(params.get("timezone")));
        } else if ("encode".equals(operation)) {
            mapped.setEncoding(firstString(params.get("encoding"), params.get("format")));
            mapped.setUppercase(booleanValue(params.get("uppercase")));
        } else if ("map_boolean".equals(operation)) {
            mapped.setTrueValue(stringValue(params.get("trueValue")));
            mapped.setFalseValue(stringValue(params.get("falseValue")));
            mapped.setNullValue(stringValue(params.get("nullValue")));
        } else if ("normalize_string".equals(operation)) {
            mapped.setNullValue(stringValue(params.get("nullValue")));
        } else {
            return null;
        }
        return mapped;
    }

    private Integer integerValue(Object value) {
        return value instanceof Integer ? (Integer) value : null;
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private String firstString(Object... values) {
        for (Object value : values) {
            String resolved = stringValue(value);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static final class JdbcCanonicalRecord implements CanonicalRecord {

        private final RecordKey key;
        private final Map<String, CanonicalValue> values;

        private JdbcCanonicalRecord(RecordKey key, Map<String, CanonicalValue> values) {
            this.key = key;
            this.values = values;
        }

        @Override
        public RecordKey getKey() {
            return key;
        }

        @Override
        public Map<String, CanonicalValue> getValues() {
            return values;
        }
    }

    private final class JdbcRecordIterator implements CloseableIterator<CanonicalRecord> {

        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final List<String> selectColumns;
        private final CompareSegment segment;
        private final SchemaDescriptor schemaDescriptor;
        private boolean prepared;
        private boolean hasNext;
        private boolean closed;

        private JdbcRecordIterator(Connection connection,
                                   PreparedStatement statement,
                                   ResultSet resultSet,
                                   List<String> selectColumns,
                                   CompareSegment segment,
                                   SchemaDescriptor schemaDescriptor) {
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.selectColumns = selectColumns;
            this.segment = segment;
            this.schemaDescriptor = schemaDescriptor;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            if (!prepared) {
                advance();
            }
            return hasNext;
        }

        @Override
        public CanonicalRecord next() {
            if (!hasNext()) {
                throw new ConnectorException("No more JDBC records available");
            }
            try {
                CanonicalRecord record = readRecord(resultSet, selectColumns, segment, schemaDescriptor);
                prepared = false;
                return record;
            } catch (SQLException e) {
                throw new ConnectorException("Failed to read JDBC record", e);
            }
        }

        private void advance() {
            try {
                hasNext = resultSet.next();
                prepared = true;
                if (!hasNext) {
                    close();
                }
            } catch (SQLException e) {
                throw new ConnectorException("Failed to advance JDBC record cursor", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly(resultSet);
            closeQuietly(statement);
            closeQuietly(connection);
        }
    }

    public static class JdbcTypeNormalizationRule {

        private Integer precision;
        private Boolean rounding;
        private String format;
        private String timezone;
        private String encoding;
        private Boolean uppercase;
        private String trueValue;
        private String falseValue;
        private String nullValue;

        public Integer getPrecision() {
            return precision;
        }

        public void setPrecision(Integer precision) {
            this.precision = precision;
        }

        public Boolean getRounding() {
            return rounding;
        }

        public void setRounding(Boolean rounding) {
            this.rounding = rounding;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        public Boolean getUppercase() {
            return uppercase;
        }

        public void setUppercase(Boolean uppercase) {
            this.uppercase = uppercase;
        }

        public String getTrueValue() {
            return trueValue;
        }

        public void setTrueValue(String trueValue) {
            this.trueValue = trueValue;
        }

        public String getFalseValue() {
            return falseValue;
        }

        public void setFalseValue(String falseValue) {
            this.falseValue = falseValue;
        }

        public String getNullValue() {
            return nullValue;
        }

        public void setNullValue(String nullValue) {
            this.nullValue = nullValue;
        }
    }
}
