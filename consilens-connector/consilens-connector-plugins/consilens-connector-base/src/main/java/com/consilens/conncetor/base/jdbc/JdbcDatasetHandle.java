package com.consilens.conncetor.base.jdbc;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.LegacyTypeMapper;
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
import com.consilens.connector.api.dataset.SplitOptions;

import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.ConnectorNativeType;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.DerivedCompareColumns;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Clob;
import java.sql.Blob;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.function.Function;

@Slf4j
public class JdbcDatasetHandle implements DatasetHandle, RelationalDatasetSupport, com.consilens.connector.api.dataset.DialectProvider {

    private static final int DEFAULT_STREAM_FETCH_SIZE = 1000;

    // readRecord 每行调用；下面三份缓存把"每行重建列名映射/解析 key 列/建 key 集合"的开销摊到每段一次。
    // 20k 行 × 37 列的场景下，仅 foldedFields 重建就是 74 万次 map 写入。
    private SchemaDescriptor cachedFoldSchema;
    private Map<String, FieldDescriptor> cachedFoldedFields = Collections.emptyMap();
    private CompareSegment cachedKeySegment;
    private List<String> cachedKeyColumns = Collections.emptyList();
    private Set<String> cachedKeyColumnSet = Collections.emptySet();
    private static final Pattern PARTITION_PATTERN = Pattern.compile("PARTITION\\s+BY\\s+.*?\\(([^\\)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final String connectorName;
    private final ResourceLocator resource;
    /**
     * 惰性初始化：createMetadata 里的分区属性探测会通过 JDBC 执行
     * {@code SHOW CREATE TABLE}，构造期不能触发任何数据库连接。
     */
    private volatile DatasetMetadata metadata;
    private final String connectorType;
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
                             String connectorType,
                             Function<Map<String, ?>, DatabaseDialect> dialectFactory,
                             Map<String, Object> connection,
                             ResourceLocator resource,
                             ReadOptions readOptions) {
        this.resource = resource;
        this.connectorName = connectorName;
        this.connectorType = connectorType;
        this.connection = connection != null ? new LinkedHashMap<>(connection) : Map.of();
        this.readOptions = readOptions;
        this.dialect = dialectFactory.apply(toLegacyNormalization(readOptions));
        this.recordScanner = this::scanRecords;
        this.hashProvider = this::digestSegment;
        this.filterPushdownProvider = predicate -> PushdownResult.builder()
                .pushedPredicate(predicate)
                .residualPredicate(null)
                .build();
        // metadata 延迟到 getMetadata() 首次调用时再构建（见该字段注释）。
    }

    @Override
    public ResourceLocator getResource() {
        return resource;
    }

    @Override
    public DatasetMetadata getMetadata() {
        DatasetMetadata local = metadata;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (metadata == null) {
                metadata = createMetadata(connectorName, connectorType, connection, resource, readOptions);
            }
            return metadata;
        }
    }

    @Override
    public SchemaDescriptor getSchema() throws ConnectorException {
        SchemaDescriptor local = schema;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (schema == null) {
                schema = discoverSchema();
            }
            return schema;
        }
    }

    @Override
    public Optional<RecordScanner> getRecordScanner() {
        return Optional.of(recordScanner);
    }

    @Override
    public Optional<SplitPlanner> getSplitPlanner() {
        return Optional.of(this::planKeyRangeSplits);
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
    public String getConnectorType() {
        return connectorType;
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
        if (isSqlResource(resource)) {
            throw new ConnectorException("SQL resource does not expose a physical TablePath");
        }
        return resolveTablePath(resource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getOrCreateDataSource().getConnection();
    }

    private DatasetMetadata createMetadata(String connectorName,
                                           String connectorType,
                                           Map<String, Object> connection,
                                           ResourceLocator resource,
                                           ReadOptions readOptions) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("databaseType", connectorType);
        attributes.put("resourceType", resource != null ? resource.getType() : null);
        attributes.put("connectorName", connectorName);
        if (dialect.discoversPartitionAttributes() && !isSqlResource(resource)) {
            attributes.putAll(discoverPartitionAttributes(resource));
        }

        EnumSet<ConnectorCapability> capabilities = EnumSet.of(
                ConnectorCapability.SCHEMA_DISCOVERY,
                ConnectorCapability.FILTER_PUSHDOWN,
                ConnectorCapability.PROJECTION_PUSHDOWN,
                ConnectorCapability.SERVER_SIDE_HASH,
                ConnectorCapability.ORDERED_SCAN,
                ConnectorCapability.STREAM_SCAN
        );
        if (!isSqlResource(resource)) {
            capabilities.add(ConnectorCapability.SERVER_SIDE_JOIN);
        }

        // 方言侧按需裁剪能力/补充属性（如 Oracle 的 SQL 侧摘要限制），base 不按连接器类型分支
        dialect.adjustDatasetCapabilities(capabilities, attributes, () -> hasLobColumns(resource));

        // Presto（prestodb/presto:latest = 0.298.1 + presto-jdbc 0.286）无需在此裁剪能力：
        // 曾经的"join 取数卡死"根因在客户端——无参 SQL 走 prepareStatement 会命中服务端
        // PREPARE 路径并挂起，已由 AbstractDatabaseAdapter 按 supportsServerSidePrepare()
        // 改用普通 Statement。checksum 与 join 两条下推均已实测可用（edge/typediff/
        // scale_100k/edge_dup 全绿，含 10 万行与重复键）。仅 alltype 表受上游 catalog
        // 限制（INT UNSIGNED → integer(32 位)，值 2147483650 越界）无法经 Presto 读取。

        return DatasetMetadata.builder()
                .logicalName(resource != null ? (resource.getName() != null ? resource.getName() : resource.getPath()) : null)
                .executionDomainId(buildExecutionDomainId(connectorType, connection))
                .capabilities(new CapabilitySet(capabilities))
                .attributes(attributes)
                .build();
    }

    private String buildExecutionDomainId(String connectorType, Map<String, Object> connection) {
        Object jdbcUrl = connection != null ? connection.get("url") : null;
        return connectorType + ":" + String.valueOf(jdbcUrl);
    }

    /**
     * 给裸类型名补精度/标度：JDBC 元数据的 TYPE_NAME 常不带括号参数（Oracle 全是
     * {@code NUMBER}、PG 的 {@code numeric}），而类型映射要靠 precision/scale 区分
     * 整数与定点——不补的话 {@code NUMBER(19)} 会被当成 DECIMAL，本地比对把主键
     * 归一化成 {@code 1.0000}（对侧 MySQL 是 {@code 1}），整表判为不一致。
     */
    private String qualifiedTypeName(String typeName, ResultSet columns) throws SQLException {
        int precision = columns.getInt("COLUMN_SIZE");
        int scale = columns.getInt("DECIMAL_DIGITS");
        return refineTypeName(typeName, precision, scale);
    }

    /**
     * 给裸类型名补精度/标度：JDBC 元数据的 TYPE_NAME 常不带括号参数（Oracle 全是
     * {@code NUMBER}、PG 的 {@code numeric}），而类型映射要靠 precision/scale 区分
     * 整数与定点——不补的话 {@code NUMBER(38,10)} 会被当成 DOUBLE（%.6f），
     * 定点列的"第 5 位起是窗口"的口径失效（实测 oracle 的 c_high_precision 第 6 位差异被误报）。
     */
    private String refineTypeName(String typeName, int precision, int scale) {
        if (typeName == null || typeName.contains("(")) {
            return typeName;
        }
        String upper = typeName.trim().toUpperCase(Locale.ROOT);
        if (!upper.equals("NUMBER") && !upper.equals("NUMERIC") && !upper.equals("DECIMAL")) {
            return typeName;
        }
        if (precision <= 0) {
            return typeName;
        }
        return scale > 0 ? upper + "(" + precision + "," + scale + ")" : upper + "(" + precision + ")";
    }

    /**
     * 表是否含 LOB 列（CLOB/BLOB 等 SQL 侧无法归一化的类型）。
     * 元数据查询失败时按"不含"处理，避免因探测本身让 open 失败。
     */
    private boolean hasLobColumns(ResourceLocator resource) {
        if (resource == null || isSqlResource(resource)) {
            return false;
        }
        try {
            TablePath path = resolveTablePath(resource);
            String schema = path.getSchema().orElse(null);
            String table = path.getTableName();
            try (Connection connection = getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = connection.getCatalog();
                // 目录大小写与元数据存储不一致时（Oracle 存大写、资源名小写）直接匹配不到，
                // 大小写两种写法都试一次。
                for (String candidate : new String[] { table, table.toUpperCase(Locale.ROOT),
                        table.toLowerCase(Locale.ROOT) }) {
                    try (ResultSet columns = metaData.getColumns(catalog, schema, candidate, null)) {
                        while (columns.next()) {
                            String typeName = columns.getString("TYPE_NAME");
                            if (typeName == null) {
                                continue;
                            }
                            String upper = typeName.toUpperCase(Locale.ROOT);
                            if (upper.contains("CLOB") || upper.contains("BLOB") || upper.contains("NCLOB")) {
                                log.info("LOB column detected in {}.{} ({}): SQL-side normalization unavailable, "
                                        + "falling back to local comparison path", schema, table, upper);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("LOB column discovery failed for {}: {}", resource.getPath(), e.getMessage());
        }
        return false;
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
        config.setMaximumPoolSize(resolveMaxPoolSize(connection));
        String initSql = dialect.getConnectionInitSql();
        if (initSql != null && !initSql.isBlank()) {
            config.setConnectionInitSql(initSql);
        }
        config.setPoolName("connector-" + connectorName + "-" + Integer.toHexString(System.identityHashCode(this)));
        return config;
    }

    private int resolveMaxPoolSize(Map<String, Object> connection) {
        Object raw = connection.get("maxPoolSize");
        if (raw instanceof Number) {
            return Math.max(((Number) raw).intValue(), 1);
        }
        if (raw != null) {
            try {
                return Math.max(Integer.parseInt(raw.toString().trim()), 1);
            } catch (NumberFormatException e) {
                log.warn("Invalid maxPoolSize '{}', falling back to default", raw);
            }
        }
        return DEFAULT_MAX_POOL_SIZE;
    }

    private static final int DEFAULT_MAX_POOL_SIZE = 10;

    private String requireJdbcUrl() {
        Object rawUrl = connection.get("url");
        if (!(rawUrl instanceof String) || ((String) rawUrl).trim().isEmpty()) {
            throw new ConnectorException("JDBC connector requires connection.url");
        }
        return ((String) rawUrl).trim();
    }

    private SchemaDescriptor discoverSchema() {
        if (isSqlResource(resource)) {
            String sql = requireSqlResource();
            log.debug("Discovering JDBC schema for SQL resource");
            try (Connection jdbcConnection = openConnection()) {
                return discoverSchemaFromSqlResultSet(jdbcConnection, sql);
            } catch (SQLException e) {
                throw new ConnectorException("Failed to discover JDBC schema for SQL resource", e);
            }
        }

        TablePath tablePath = resolveTablePath(resource);
        log.debug("Discovering JDBC schema for {}", tablePath.getFullPath());
        try (Connection jdbcConnection = openConnection()) {
            SchemaDescriptor viaMetadata = null;
            // 部分方言的 DatabaseMetaData.getColumns 会偶发挂起且无法设超时（典型：Presto），
            // 由方言声明能否安全使用 JDBC 元数据；不支持时直接走 result-set 发现。
            if (dialect.supportsJdbcMetadata()) {
                try (ResultSet columns = metadataColumns(jdbcConnection, tablePath)) {
                List<FieldDescriptor> fields = new ArrayList<>();
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String typeName = columns.getString("TYPE_NAME");
                    boolean nullable = DatabaseMetaData.columnNullable == columns.getInt("NULLABLE");
                    int ordinal = columns.getInt("ORDINAL_POSITION");
                    TypeDescriptor typeDescriptor = dialect.getDataTypeHandler()
                            .convertToTypeDescriptor(qualifiedTypeName(typeName, columns)).toBuilder()
                            .nullable(nullable)
                            .build();
                    fields.add(FieldDescriptor.builder()
                            .name(columnName)
                            .canonicalType(LegacyTypeMapper.toCanonicalType(typeDescriptor))
                            .typeDescriptor(typeDescriptor)
                            .nativeType(ConnectorNativeType.builder().name(typeName).declaration(typeName).build())
                            .nullable(nullable)
                            .ordinal(ordinal)
                            .attributes(Map.of("sourceType", typeName))
                            .build());
                }
                if (!fields.isEmpty()) {
                    Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
                    for (FieldDescriptor field : fields) {
                        fieldMap.put(field.getName(), field);
                    }
                    viaMetadata = SchemaDescriptor.builder()
                            .fields(List.copyOf(fields))
                            .fieldMap(fieldMap)
                            .build();
                }
            } catch (RuntimeException e) {
                // Some MySQL-protocol engines (e.g. Doris) emit non-numeric metadata
                // values (COLUMN_SIZE="*") that make the driver's DatabaseMetaData
                // blow up mid-iteration; fall back to the result-set based discovery.
                log.info("DatabaseMetaData.getColumns failed for {} ({}), falling back to result-set discovery",
                        tablePath.getFullPath(), e.getMessage());
                }
            }
            if (viaMetadata == null) {
                return discoverSchemaFromResultSet(jdbcConnection, tablePath);
            }
            return viaMetadata;
        } catch (SQLException e) {
            throw new ConnectorException("Failed to discover JDBC schema for " + tablePath.getFullPath(), e);
        }
    }

    private ResultSet metadataColumns(Connection jdbcConnection, TablePath tablePath) throws SQLException {
        DatabaseMetaData databaseMetaData = jdbcConnection.getMetaData();
        String catalog = tablePath.getCatalog().orElse(null);
        String schemaName = tablePath.getSchema().orElse(null);
        String resolved = resolveTableName(databaseMetaData, catalog, schemaName, tablePath.getTableName());
        return databaseMetaData.getColumns(catalog, schemaName, resolved, null);
    }

    /**
     * 解析表的真实大小写名：Oracle 元数据存大写、资源名常为小写，直接用资源名查列会返回空集，
     * 引擎会退到 ResultSetMetaData 路径（拿不到 DECIMAL_DIGITS，定点列被当成 DOUBLE）。
     * 用 getTables 按大小写不敏感匹配，取元数据里的真实名字（避免 beforeFirst，前向结果集不支持）。
     */
    private String resolveTableName(DatabaseMetaData databaseMetaData, String catalog, String schema,
                                    String table) {
        for (String candidate : new String[] { table, table.toUpperCase(Locale.ROOT),
                table.toLowerCase(Locale.ROOT) }) {
            try (ResultSet tables = databaseMetaData.getTables(catalog, schema, candidate, null)) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (name != null && name.equalsIgnoreCase(table)) {
                        return name;
                    }
                }
            } catch (SQLException e) {
                log.debug("Table name resolution failed for {}: {}", table, e.getMessage());
            }
        }
        return table;
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
            return toSchemaDescriptor(resultSet.getMetaData());
        }
    }

    private SchemaDescriptor discoverSchemaFromSqlResultSet(Connection jdbcConnection, String sqlResource) throws SQLException {
        String sql = dialect.getSqlQueryGenerator().getSelectSQLFromSql(
                sqlResource, List.of("*"), "1 = 0", null);
        try (PreparedStatement statement = jdbcConnection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return toSchemaDescriptor(resultSet.getMetaData());
        }
    }

    private SchemaDescriptor toSchemaDescriptor(ResultSetMetaData resultSetMetaData) throws SQLException {
        List<FieldDescriptor> fields = new ArrayList<>();
        Map<String, FieldDescriptor> fieldMap = new LinkedHashMap<>();
        for (int index = 1; index <= resultSetMetaData.getColumnCount(); index++) {
            String columnName = resultSetMetaData.getColumnLabel(index);
            String typeName = resultSetMetaData.getColumnTypeName(index);
            // Oracle 的 getColumnTypeName 只给裸 "NUMBER"（无精度/标度）：不补参数时
            // 整数列会被当成 DECIMAL（"54.0000" vs "54"）、定点列会被当成 DOUBLE
            // （第 5 位起本该是窗口的差异被误报）。用 JDBC 的 precision/scale 补全。
            String refinedTypeName = refineTypeName(typeName,
                    resultSetMetaData.getPrecision(index), resultSetMetaData.getScale(index));
            TypeDescriptor typeDescriptor = dialect.getDataTypeHandler().convertToTypeDescriptor(refinedTypeName).toBuilder()
                    .nullable(resultSetMetaData.isNullable(index) != ResultSetMetaData.columnNoNulls)
                    .build();
            FieldDescriptor field = FieldDescriptor.builder()
                    .name(columnName)
                    .canonicalType(LegacyTypeMapper.toCanonicalType(typeDescriptor))
                    .typeDescriptor(typeDescriptor)
                    .nativeType(ConnectorNativeType.builder().name(typeName).declaration(typeName).build())
                    .nullable(typeDescriptor.isNullable())
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

    private CloseableIterator<CanonicalRecord> scanRecords(CompareSegment segment) {
        SchemaDescriptor schemaDescriptor = segment.getSchema() != null ? segment.getSchema() : getSchema();
        List<String> selectColumns = selectColumns(segment, schemaDescriptor);
        TablePath tablePath = isSqlResource(resource) ? null : resolveTablePath(resource);
        String sql = buildScanSql(segment, selectColumns, tablePath);
        Connection jdbcConnection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            jdbcConnection = openConnection();
            configureStreamingConnection(jdbcConnection);
            statement = jdbcConnection.prepareStatement(sql);
            applyReadOptions(statement);
            resultSet = statement.executeQuery();
            log.debug("Started JDBC ordered scan for {} with SQL: {}", resourceDisplayName(), sql);
            return new JdbcRecordIterator(jdbcConnection, statement, resultSet, selectColumns, segment, schemaDescriptor);
        } catch (SQLException e) {
            closeQuietly(resultSet);
            closeQuietly(statement);
            closeQuietly(jdbcConnection);
            throw new ConnectorException("Failed to scan JDBC dataset " + resourceDisplayName(), e);
        }
    }

    private SegmentDigest digestSegment(CompareSegment segment, HashOptions options) {
        SchemaDescriptor schemaDescriptor = segment.getSchema() != null ? segment.getSchema() : getSchema();
        TablePath tablePath = isSqlResource(resource) ? null : resolveTablePath(resource);
        List<String> keyColumns = keyColumns(segment);
        List<String> comparisonColumns = comparisonColumns(segment.getComparisons(), schemaDescriptor, keyColumns);
        List<String> checksumColumns = new ArrayList<>(keyColumns);
        checksumColumns.addAll(comparisonColumns);
        String sql;
        if (!isSqlResource(resource)) {
            String whereClause = buildWhereClause(segment, keyColumns);
            sql = dialect.getSqlQueryGenerator().getChecksumSQL(
                    tablePath.getSchema().orElse(null),
                    tablePath.getTableName(),
                    keyColumns,
                    checksumColumns,
                     columnTypes(schemaDescriptor, checksumColumns),
                     whereClause,
                    resolveChecksumAlgorithm(options));
        } else {
            List<String> digestColumns = selectColumns(segment, schemaDescriptor);
            String scanSql = buildScanSql(segment, digestColumns, tablePath, false);
            sql = dialect.getSqlQueryGenerator().getChecksumSQLFromSql(
                    scanSql,
                    keyColumns,
                    digestColumns,
                    columnTypes(schemaDescriptor, digestColumns),
                    null,
                    resolveChecksumAlgorithm(options));
        }
        long startTime = System.currentTimeMillis();
        log.info("Executing checksum pushdown for {}", resourceDisplayName());
        log.debug("Checksum SQL for {}: {}", resourceDisplayName(), sql);
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
                    resourceDisplayName(),
                    System.currentTimeMillis() - startTime,
                    digest.getRowCount());
            return digest;
        } catch (SQLException e) {
            throw new ConnectorException("Failed to hash JDBC dataset " + resourceDisplayName(), e);
        }
    }

    private String buildScanSql(CompareSegment segment,
                                List<String> selectColumns,
                                TablePath tablePath) {
        return buildScanSql(segment, selectColumns, tablePath, true);
    }

    private String buildScanSql(CompareSegment segment,
                                List<String> selectColumns,
                                TablePath tablePath,
                                boolean includeOrderBy) {
        if (isSqlResource(resource)) {
            return buildSqlResourceScanSql(segment, selectColumns, requireSqlResource(), includeOrderBy);
        }
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

    private String buildSqlResourceScanSql(CompareSegment segment,
                                           List<String> selectColumns,
                                           String sqlResource,
                                           boolean includeOrderBy) {
        List<String> selectExpressions = new ArrayList<>();
        for (String column : selectColumns) {
            selectExpressions.add(dialect.getCapabilityProvider().quote(column));
        }
        List<String> keyColumns = keyColumns(segment);
        String whereClause = buildWhereClause(segment, keyColumns);
        // 走 generator 的统一入口：ORDER BY 必须与 Java 归并比较用同一归一化形态，
        // 且会去除 SQL 资源末尾分号（否则包装成派生表后语法错误）。
        String sql = dialect.getSqlQueryGenerator().getSelectSQLFromSql(
                sqlResource,
                selectExpressions,
                whereClause,
                includeOrderBy && !keyColumns.isEmpty() ? keyColumns : null);
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
        List<String> keyColumns = keyColumnsCached(segment);
        Set<String> keyColumnSet = keyColumnSetCached(segment);
        List<Object> keyParts = new ArrayList<>(keyColumns.size());
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        Map<String, FieldDescriptor> fieldMap = schemaDescriptor.getFieldMap() != null
                ? schemaDescriptor.getFieldMap()
                : Collections.emptyMap();
        Map<String, FieldDescriptor> foldedFields = foldedFieldsCached(schemaDescriptor, fieldMap);
        for (int index = 0; index < selectColumns.size(); index++) {
            String column = selectColumns.get(index);
            FieldDescriptor field = fieldMap.get(column);
            if (field == null) {
                // Oracle 元数据列名全大写，任务列名小写：大小写敏感查找会落空，
                // CanonicalValue 的类型会退化成 UNKNOWN（本地归一化随即失真）
                field = foldedFields.get(column.toLowerCase(Locale.ROOT));
            }
            // 日期时间列按字符串取：Oracle 驱动对 TIMESTAMP WITH TIME ZONE 返回
            // oracle.sql.TIMESTAMPTZ 句柄（无 stringValue()，toString 只有句柄地址），
            // 两侧句柄必然不同 → 全表误报。getString 给的是可解析的 SQL 文本。
            Object value = isTemporal(field)
                    ? resultSet.getString(index + 1)
                    : toPortableValue(materializeLob(resultSet.getObject(index + 1)));
            if (keyColumnSet.contains(column.toLowerCase(Locale.ROOT))) {
                keyParts.add(value);
            }
            values.put(column, CanonicalValue.builder()
                    .type(resolveCanonicalType(field))
                    .value(value)
                    .build());
        }
        return new JdbcCanonicalRecord(RecordKey.builder().parts(keyParts).build(), values);
    }

    private List<String> keyColumnsCached(CompareSegment segment) {
        if (segment != cachedKeySegment) {
            cachedKeySegment = segment;
            cachedKeyColumns = keyColumns(segment);
            Set<String> folded = new LinkedHashSet<>();
            for (String key : cachedKeyColumns) {
                if (key != null) {
                    folded.add(key.toLowerCase(Locale.ROOT));
                }
            }
            cachedKeyColumnSet = folded;
        }
        return cachedKeyColumns;
    }

    private Set<String> keyColumnSetCached(CompareSegment segment) {
        keyColumnsCached(segment);
        return cachedKeyColumnSet;
    }

    private Map<String, FieldDescriptor> foldedFieldsCached(SchemaDescriptor schema,
                                                            Map<String, FieldDescriptor> fieldMap) {
        if (schema != cachedFoldSchema) {
            cachedFoldSchema = schema;
            Map<String, FieldDescriptor> folded = new LinkedHashMap<>();
            for (Map.Entry<String, FieldDescriptor> entry : fieldMap.entrySet()) {
                folded.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
            cachedFoldedFields = folded;
        }
        return cachedFoldedFields;
    }

    private boolean isTemporal(FieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String canonical = field.getTypeDescriptor() != null
                ? field.getTypeDescriptor().getType() != null ? field.getTypeDescriptor().getType().name() : null
                : field.getCanonicalType();
        if (canonical == null) {
            return false;
        }
        String upper = canonical.toUpperCase(Locale.ROOT);
        return upper.contains("DATE") || upper.contains("TIME");
    }

    /**
     * 把驱动专有类型折叠成通用 Java 值：Oracle 的 {@code oracle.sql.TIMESTAMP} 等
     * 既不能被 Jackson 序列化（差异写入 sink 时抛异常），也不能被 Java 侧归一化器识别。
     * 只保留标准类型（字符串/数字/布尔/字节/日期时间），其余取其字符串形态。
     */
    private Object toPortableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof byte[] || value instanceof java.util.Date
                || value instanceof java.time.temporal.Temporal) {
            return value;
        }
        // Oracle 驱动的 oracle.sql.* 句柄：取其 stringValue() 的 SQL 形态文本。
        // toString() 只给句柄地址（oracle.sql.TIMESTAMPTZ@1a2b），两侧句柄必然不同，
        // 会把所有行判成差异（实测 c_timestamptz 全表误报）。
        if (value.getClass().getName().startsWith("oracle.sql.")) {
            try {
                Object text = value.getClass().getMethod("stringValue").invoke(value);
                if (text != null) {
                    return text.toString();
                }
            } catch (Exception ignored) {
                // 反射失败时落到下面的 toString 兜底
            }
        }
        String text = value.toString();
        log.debug("Converting driver-specific value {} to text: {}", value.getClass().getName(), text);
        return text;
    }

    /**
     * LOB 物化：驱动对 CLOB/BLOB 返回 {@link Clob}/{@link Blob} 句柄，直接 toString
     * 会得到 "oracle.sql.CLOB@..." 之类的句柄串，本地归一化必然出错。取回实际内容
     * （CLOB → String，BLOB → byte[]），与其它方言的字符串/二进制值同形。
     */
    private Object materializeLob(Object value) throws SQLException {
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            long length = clob.length();
            return length > Integer.MAX_VALUE ? clob.getSubString(1, Integer.MAX_VALUE)
                    : clob.getSubString(1, (int) length);
        }
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            long length = blob.length();
            return length > Integer.MAX_VALUE ? blob.getBytes(1, Integer.MAX_VALUE)
                    : blob.getBytes(1, (int) length);
        }
        return value;
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
            // host/port/database are URL constituents injected by server-side
            // datasource references; strict drivers (trino/presto, clickhouse-jdbc)
            // reject them as unknown properties
            case "host":
            case "port":
            case "database":
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
        if (dialect.requiresTransactionForStreamingReads() && jdbcConnection.getAutoCommit()) {
            jdbcConnection.setAutoCommit(false);
        }
    }

    private List<String> selectColumns(CompareSegment segment, SchemaDescriptor schemaDescriptor) {
        List<String> keyColumns = keyColumns(segment);
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        // 键名可能来自用户配置（小写），而数据库元数据可能是大写（Oracle）。
        // 统一解析成 schema 中的实际列名，避免同一列被选两次。
        for (String key : keyColumns) {
            columns.add(resolveColumnName(key, schemaDescriptor));
        }
        columns.addAll(comparisonColumns(segment.getComparisons(), schemaDescriptor, keyColumns));
        return List.copyOf(columns);
    }

    private static String resolveColumnName(String name, SchemaDescriptor schemaDescriptor) {
        if (name == null || schemaDescriptor == null || schemaDescriptor.getFields() == null) {
            return name;
        }
        for (FieldDescriptor field : schemaDescriptor.getFields()) {
            if (field.getName() != null && field.getName().equalsIgnoreCase(name)) {
                return field.getName();
            }
        }
        return name;
    }

    private List<String> comparisonColumns(ComparisonSpec comparisons,
                                           SchemaDescriptor schemaDescriptor,
                                           List<String> keyColumns) {
        Set<String> excluded = excludedColumns(comparisons);
        if (comparisons != null && comparisons.getFields() != null && !comparisons.getFields().isEmpty()) {
            List<String> result = new ArrayList<>();
            for (String field : comparisons.getFields()) {
                if (field != null && !isExcluded(excluded, field)) {
                    result.add(resolveColumnName(field, schemaDescriptor));
                }
            }
            return List.copyOf(result);
        }
        if (schemaDescriptor == null || schemaDescriptor.getFields() == null) {
            return List.of();
        }
        Set<String> keys = new java.util.HashSet<>();
        for (String key : keyColumns) {
            if (key != null) {
                keys.add(key.toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<String> result = new ArrayList<>();
        for (FieldDescriptor field : schemaDescriptor.getFields()) {
            if (field.getName() != null
                    && !keys.contains(field.getName().toLowerCase(java.util.Locale.ROOT))
                    && !isExcluded(excluded, field.getName())
                    && !DerivedCompareColumns.isDerived(field.getName())) {
                result.add(field.getName());
            }
        }
        return result;
    }

    private static boolean isExcluded(Set<String> excluded, String field) {
        return field != null && excluded.contains(field.toLowerCase(java.util.Locale.ROOT));
    }

    private Set<String> excludedColumns(ComparisonSpec comparisons) {
        if (comparisons == null || comparisons.getExclude() == null || comparisons.getExclude().isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String field : comparisons.getExclude()) {
            if (field != null && !field.trim().isEmpty()) {
                result.add(field.trim().toLowerCase(java.util.Locale.ROOT));
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
        Map<String, FieldDescriptor> folded = new LinkedHashMap<>();
        for (Map.Entry<String, FieldDescriptor> entry : fieldMap.entrySet()) {
            folded.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        for (String column : columns) {
            FieldDescriptor field = fieldMap.get(column);
            if (field == null && column != null) {
                field = folded.get(column.toLowerCase(Locale.ROOT));
            }
            result.put(column, field != null ? resolveDataType(resolveCanonicalType(field)) : DataType.UNKNOWN);
        }
        return result;
    }

    private String resolveCanonicalType(FieldDescriptor field) {
        if (field == null) {
            return null;
        }
        if (field.getTypeDescriptor() != null) {
            return LegacyTypeMapper.toCanonicalType(field.getTypeDescriptor());
        }
        return field.getCanonicalType();
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
        SegmentSplit split = segment.getSplit();
        if (split != null && !(split instanceof KeyRangeSplit) && !(split instanceof OffsetLimitSplit)) {
            throw new ConnectorException("Unsupported JDBC split type: " + split.getClass().getSimpleName());
        }
        return new WhereClauseBuilder(dialect)
                .addBaseFilter(segment.getFilter())
                .addSplit(segment.getSplit(), keyColumns)
                .build();
    }

    private List<SegmentSplit> planKeyRangeSplits(CompareSegment segment, SplitOptions options) {
        validateRangeSplitResource();
        String keyColumn = validateRangeSplitKey(segment);
        String baseWhereClause = new WhereClauseBuilder(dialect)
                .addBaseFilter(segment.getFilter())
                .build();
        TablePath tablePath = resolveTablePath(resource);

        try (Connection jdbcConnection = openConnection()) {
            long rowCount = queryCount(jdbcConnection, tablePath, baseWhereClause);
            if (rowCount == 0) {
                return List.of();
            }
            rejectNullKeys(jdbcConnection, tablePath, keyColumn, baseWhereClause);
            BigDecimal minKey = queryKeyBound(jdbcConnection, tablePath, keyColumn, true, baseWhereClause);
            BigDecimal maxKey = queryKeyBound(jdbcConnection, tablePath, keyColumn, false, baseWhereClause);
            if (minKey == null || maxKey == null) {
                throw new ConnectorException("JDBC range split key '" + keyColumn + "' must not contain null values");
            }
            return buildRangeSplits(minKey, maxKey, resolveSplitCount(options, rowCount));
        } catch (SQLException e) {
            throw new ConnectorException("Failed to plan JDBC key range splits for " + tablePath.getFullPath(), e);
        }
    }

    private void validateRangeSplitResource() {
        if (resource == null || resource.getType() == null || !"table".equalsIgnoreCase(resource.getType())) {
            throw new ConnectorException("JDBC key range splits require a table resource");
        }
    }

    private String validateRangeSplitKey(CompareSegment segment) {
        if (segment == null || segment.getKeySpec() == null || segment.getKeySpec().getFields() == null
                || segment.getKeySpec().getFields().size() != 1) {
            throw new ConnectorException("JDBC key range splits require exactly one key column");
        }
        String keyColumn = segment.getKeySpec().getFields().get(0);
        if (keyColumn == null || keyColumn.trim().isEmpty()) {
            throw new ConnectorException("JDBC key range split column must not be blank");
        }
        FieldDescriptor field = findField(getSchema(), keyColumn);
        if (field == null) {
            throw new ConnectorException("JDBC key range split column is not present in the table schema: " + keyColumn);
        }
        DataType keyType = resolveDataType(resolveCanonicalType(field));
        if (!isRangeSplitKeyType(keyType)) {
            throw new ConnectorException("JDBC key range split requires an integer, DECIMAL, or NUMERIC key column: "
                    + keyColumn);
        }
        return field.getName();
    }

    private boolean isRangeSplitKeyType(DataType keyType) {
        return keyType == DataType.TINYINT
                || keyType == DataType.SMALLINT
                || keyType == DataType.INTEGER
                || keyType == DataType.BIGINT
                || keyType == DataType.DECIMAL
                || keyType == DataType.NUMERIC;
    }

    private FieldDescriptor findField(SchemaDescriptor schemaDescriptor, String fieldName) {
        if (schemaDescriptor == null || schemaDescriptor.getFields() == null) {
            return null;
        }
        for (FieldDescriptor field : schemaDescriptor.getFields()) {
            if (field != null && fieldName.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }

    private long queryCount(Connection jdbcConnection, TablePath tablePath, String whereClause) throws SQLException {
        String sql = dialect.getSqlQueryGenerator().getCountSQL(
                tablePath.getSchema().orElse(null), tablePath.getTableName(), whereClause);
        try (PreparedStatement statement = jdbcConnection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private void rejectNullKeys(Connection jdbcConnection,
                                TablePath tablePath,
                                String keyColumn,
                                String baseWhereClause) throws SQLException {
        String nullKeyPredicate = dialect.getCapabilityProvider().quote(keyColumn) + " IS NULL";
        String whereClause = baseWhereClause == null
                ? nullKeyPredicate
                : baseWhereClause + " AND " + nullKeyPredicate;
        if (queryCount(jdbcConnection, tablePath, whereClause) > 0) {
            throw new ConnectorException("JDBC key range split key must not contain null values: " + keyColumn);
        }
    }

    private BigDecimal queryKeyBound(Connection jdbcConnection,
                                     TablePath tablePath,
                                     String keyColumn,
                                     boolean min,
                                     String whereClause) throws SQLException {
        String sql = dialect.getSqlQueryGenerator().getMinMaxKeySQL(
                tablePath.getSchema().orElse(null), tablePath.getTableName(), List.of(keyColumn), min, whereClause);
        try (PreparedStatement statement = jdbcConnection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getBigDecimal(1) : null;
        }
    }

    private int resolveSplitCount(SplitOptions options, long rowCount) {
        if (options == null) {
            throw new ConnectorException("JDBC key range split options are required");
        }
        Integer expectedSplitCount = options.getExpectedSplitCount();
        Long targetRowsPerSplit = options.getTargetRowsPerSplit();
        if (expectedSplitCount != null && targetRowsPerSplit != null) {
            throw new ConnectorException("Specify either expectedSplitCount or targetRowsPerSplit for JDBC key range splits");
        }
        if (expectedSplitCount != null) {
            if (expectedSplitCount <= 0) {
                throw new ConnectorException("expectedSplitCount must be greater than zero");
            }
            return (int) Math.min(expectedSplitCount.longValue(), rowCount);
        }
        if (targetRowsPerSplit == null || targetRowsPerSplit <= 0) {
            throw new ConnectorException("targetRowsPerSplit must be greater than zero when expectedSplitCount is absent");
        }
        long splitCount = rowCount / targetRowsPerSplit
                + (rowCount % targetRowsPerSplit == 0 ? 0 : 1);
        if (splitCount > Integer.MAX_VALUE) {
            throw new ConnectorException("Derived JDBC key range split count exceeds integer range");
        }
        return (int) splitCount;
    }

    private List<SegmentSplit> buildRangeSplits(BigDecimal minKey, BigDecimal maxKey, int splitCount) {
        if (minKey.compareTo(maxKey) == 0 || splitCount == 1) {
            return List.of(KeyRangeSplit.builder().startKey(List.of(minKey)).endKey(null).build());
        }
        int scale = Math.max(Math.max(minKey.scale(), maxKey.scale()), 0) + 16;
        BigDecimal step = maxKey.subtract(minKey)
                .divide(BigDecimal.valueOf(splitCount), scale, RoundingMode.UP);
        List<SegmentSplit> splits = new ArrayList<>(splitCount);
        BigDecimal start = minKey;
        for (int index = 1; index < splitCount; index++) {
            BigDecimal end = minKey.add(step.multiply(BigDecimal.valueOf(index)));
            if (end.compareTo(maxKey) >= 0) {
                break;
            }
            splits.add(KeyRangeSplit.builder().startKey(List.of(start)).endKey(List.of(end)).build());
            start = end;
        }
        splits.add(KeyRangeSplit.builder().startKey(List.of(start)).endKey(null).build());
        return List.copyOf(splits);
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

    private boolean isSqlResource(ResourceLocator locator) {
        return locator != null
                && locator.getType() != null
                && "sql".equalsIgnoreCase(locator.getType())
                && locator.getPath() != null
                && !locator.getPath().trim().isEmpty();
    }

    private String requireSqlResource() {
        if (!isSqlResource(resource)) {
            throw new ConnectorException("JDBC resource is not configured as SQL");
        }
        return resource.getPath().trim();
    }

    private String resourceDisplayName() {
        if (isSqlResource(resource)) {
            return "sql-resource";
        }
        return resolveTablePath(resource).getFullPath();
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

    private Map<String, Object> discoverPartitionAttributes(ResourceLocator resourceLocator) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (resourceLocator == null) {
            return attributes;
        }
        TablePath tablePath;
        try {
            tablePath = resolveTablePath(resourceLocator);
        } catch (Exception e) {
            return attributes;
        }
        try {
            Properties properties = buildConnectionProperties(connection, new Properties());
            try (Connection jdbcConnection = DriverManager.getConnection(requireJdbcUrl(), properties);
                 PreparedStatement statement = jdbcConnection.prepareStatement("SHOW CREATE TABLE " + quotedTablePath(tablePath));
                 ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return attributes;
                }
                String ddl = resultSet.getMetaData().getColumnCount() >= 2
                        ? resultSet.getString(2)
                        : resultSet.getString(1);
                if (ddl == null) {
                    return attributes;
                }
                if (ddl.toUpperCase(Locale.ROOT).contains("PARTITION BY")) {
                    attributes.put("partitioned", Boolean.TRUE);
                }
                Matcher matcher = PARTITION_PATTERN.matcher(ddl);
                if (matcher.find()) {
                    String[] columns = matcher.group(1).split(",");
                    List<String> keys = new ArrayList<>();
                    for (String column : columns) {
                        String normalized = column.replace("`", "").replace("\"", "").trim();
                        if (!normalized.isEmpty()) {
                            keys.add(normalized);
                        }
                    }
                    if (!keys.isEmpty()) {
                        attributes.put("partitionKeys", keys);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover Doris partition metadata for {}", tablePath.getFullPath(), e);
        }
        return attributes;
    }

    private String quotedTablePath(TablePath tablePath) {
        return dialect.getCapabilityProvider().quote(tablePath.getPathComponents());
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
            mapped.setComparisonMode(stringValue(params.get("comparisonMode")));
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
        private String comparisonMode;
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

        public String getComparisonMode() {
            return comparisonMode;
        }

        public void setComparisonMode(String comparisonMode) {
            this.comparisonMode = comparisonMode;
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
