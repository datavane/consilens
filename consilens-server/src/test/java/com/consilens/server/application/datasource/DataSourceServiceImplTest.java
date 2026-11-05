package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.AesGcmSecretProtector;
import com.consilens.server.support.crypto.SecretProtectorTestKeys;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDataSourceServiceTest {

    private static final Long DS_ID = 1L;

    private DataSourceRepository repository;
    private DialectSupport dialectSupport;
    private ConnectionTestService connectionTestService;
    private DatabaseDialect dialect;
    private MetadataQueryGenerator metadataQueryGenerator;
    private DataSourceServiceImpl service;
    private DataSourceServiceImpl.MetadataConnectionOpener opener;

    @BeforeEach
    void setUp() {
        repository = mock(DataSourceRepository.class);
        dialectSupport = mock(DialectSupport.class);
        connectionTestService = mock(ConnectionTestService.class);
        dialect = mock(DatabaseDialect.class);
        metadataQueryGenerator = mock(MetadataQueryGenerator.class);
        opener = mock(DataSourceServiceImpl.MetadataConnectionOpener.class);
        service = new DataSourceServiceImpl(repository, dialectSupport, connectionTestService,
                new ObjectMapper(), SecretProtectorTestKeys.protector(),
                mock(DatasourceDefinitionValidator.class), opener);
        when(dialect.getConnectorType()).thenReturn("mysql");
        when(dialect.getMetadataQueryGenerator()).thenReturn(metadataQueryGenerator);
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
    }

    @Test
    void shouldListTypesFromSpi() {
        when(dialectSupport.listTypes()).thenReturn(List.of(
                DataSourceTypeDto.builder().type("mysql").defaultPort(3306)
                        .driverClass("com.mysql.cj.jdbc.Driver").build()));

        List<DataSourceTypeDto> types = service.listTypes();

        assertEquals(1, types.size());
        assertEquals("mysql", types.get(0).getType());
        assertEquals(3306, types.get(0).getDefaultPort());
    }

    @Test
    void shouldCreateDataSource() {
        when(repository.save(any())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            record.setId(DS_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });

        DataSourceDto dto = service.create(DataSourceCreateRequest.builder()
                .name("orders-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders"))
                .build());

        assertEquals("1", dto.getId());
        assertEquals("orders-db", dto.getName());
        assertEquals("mysql", dto.getType());
        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getParamJson().contains("\"host\":\"10.0.0.5\""));
    }

    @Test
    void shouldRejectDuplicateName() {
        when(repository.findByName("dup")).thenReturn(Optional.of(DataSourceRecord.builder().id(9L).build()));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("dup").type("mysql").build()));
    }

    @Test
    void shouldMapConcurrentDuplicateNameToInputError() {
        when(repository.save(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_cs_datasource_name"));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("dup-race").type("mysql")
                        .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders"))
                        .build()));
    }

    @Test
    void shouldExposeParamWithoutPassword() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("orders-db").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"root\",\"password\":\"" + protector.protect("secret") + "\"}")
                .build()));

        DataSourceDto dto = service.get(DS_ID);

        assertTrue(dto.getParam().containsKey("host"));
        assertEquals("10.0.0.5", dto.getParam().get("host"));
        assertEquals(3306, dto.getParam().get("port"));
        assertTrue(!dto.getParam().containsKey("password"));
    }

    @Test
    void shouldGetAndList() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(repository.listAll()).thenReturn(List.of(record()));

        assertEquals("orders-db", service.get(DS_ID).getName());
        assertEquals(1, service.list().size());
    }

    @Test
    void shouldThrowNotFoundWhenMissing() {
        when(repository.findById(DS_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(DS_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.test(DS_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.getDatabases(DS_ID));
    }

    @Test
    void shouldDelete() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));

        service.delete(DS_ID);

        verify(repository).deleteById(DS_ID);
    }

    @Test
    void shouldTestUsingStoredParams() {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(connectionTestService.test(any())).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(10L).build());

        ConnectionTestResponse response = service.test(DS_ID);

        assertTrue(response.isSuccess());
        ArgumentCaptor<ConnectionTestRequest> captor = ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        ConnectionTestRequest request = captor.getValue();
        assertEquals("mysql", request.getType());
        assertEquals("10.0.0.5", request.getHost());
        assertEquals(3306, request.getPort());
        assertEquals("orders", request.getDatabase());
        assertEquals("admin", request.getUsername());
        assertEquals("secret", request.getPassword());
    }

    @Test
    void shouldListDatabases() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getSchemasSQL()).thenReturn("SELECT schema_name FROM information_schema.schemata");
        Connection connection = mockConnection("db1", "db2");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<String> databases = service.getDatabases(DS_ID);

        assertEquals(List.of("db1", "db2"), databases);
        verify(opener).open(eq(dialect), any());
    }

    @Test
    void shouldListTables() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getTablesSQL("orders")).thenReturn("SELECT table_name FROM ...");
        Connection connection = mockConnection("t1", "t2");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<String> tables = service.getTables(DS_ID, "orders");

        assertEquals(List.of("t1", "t2"), tables);
        verify(metadataQueryGenerator).getTablesSQL("orders");
    }

    @Test
    void shouldListColumns() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(metadataQueryGenerator.getTableColumnsSQL("orders", "t1")).thenReturn("SELECT ...");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("id", "name");
        when(resultSet.getString(2)).thenReturn("bigint", "varchar");
        when(resultSet.getString(3)).thenReturn("NO", "YES");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<MetadataColumnDto> columns = service.getColumns(DS_ID, "orders", "t1");

        assertEquals(2, columns.size());
        assertEquals("id", columns.get(0).getName());
        assertEquals("bigint", columns.get(0).getDataType());
        assertEquals(false, columns.get(0).getNullable());
        assertEquals("name", columns.get(1).getName());
        assertEquals(true, columns.get(1).getNullable());
    }

    @Test
    void shouldFailMetadataWhenConnectionFails() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(record()));
        when(opener.open(eq(dialect), any()))
                .thenThrow(new IllegalStateException("Connection refused: 10.0.0.5:3306"));

        assertThrows(IllegalStateException.class, () -> service.getDatabases(DS_ID));
    }

    @Test
    void shouldFailForUnsupportedTypeInMetadata() throws Exception {
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("x").type("unknown")
                .paramJson("{\"host\":\"8.8.8.8\"}")
                .build()));
        when(dialectSupport.find("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getDatabases(DS_ID));
        verify(opener, never()).open(any(), any());
    }

    @Test
    void shouldEncryptPasswordWhenKeyConfigured() {
        AesGcmSecretProtector crypto = SecretProtectorTestKeys.protector();
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto,
                mock(DatasourceDefinitionValidator.class), opener);
        when(repository.save(any())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            record.setId(DS_ID);
            return record;
        });

        encryptedService.create(DataSourceCreateRequest.builder()
                .name("secure-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "password", "secret"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().getParamJson();
        assertTrue(stored.contains("host\":\"10.0.0.5"));
        assertTrue(stored.contains("password\":\""));
        assertTrue(!stored.contains("\"password\":\"secret\""));
    }

    @Test
    void shouldRejectInvalidParamHost() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("x").type("mysql")
                        .param(Map.of("host", ""))
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("y").type("mysql")
                        .param(Map.of("host", "bad host!"))
                        .build()));
    }

    @Test
    void shouldRejectInvalidParamDatabase() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("z").type("mysql")
                        .param(Map.of("host", "10.0.0.5", "database", "db?ssl=true"))
                        .build()));
    }

    @Test
    void shouldRejectInvalidParamSidAndSchema() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("a").type("oracle")
                        .param(Map.of("host", "10.0.0.5", "sid", "ORCL?foo=1"))
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("b").type("postgresql")
                        .param(Map.of("host", "10.0.0.5", "schema", "pub;drop"))
                        .build()));
    }

    @Test
    void shouldAcceptValidSidSchemaAndProperties() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(DataSourceCreateRequest.builder().name("c").type("oracle")
                .param(Map.of("host", "10.0.0.5", "sid", "ORCL", "port", 1521))
                .build());
        service.create(DataSourceCreateRequest.builder().name("d").type("postgresql")
                .param(Map.of("host", "10.0.0.5", "schema", "public"))
                .build());
        service.create(DataSourceCreateRequest.builder().name("e").type("mysql")
                .param(Map.of("host", "10.0.0.5",
                        "properties", "useSSL=false&socketTimeout=10000"))
                .build());
    }

    @Test
    void shouldRejectInvalidParamProperties() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 分号/空格/括号等不在 properties 白名单内
        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder().name("f").type("mysql")
                        .param(Map.of("host", "10.0.0.5", "properties", "useSSL=false;autoReconnect=true"))
                        .build()));
    }

    @Test
    void shouldGetTypeConfigFromDialectBuilder() {
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
        when(dialect.getDataSourceConfigBuilder())
                .thenReturn(new com.consilens.conncetor.base.BaseDataSourceConfigBuilder());

        List<com.consilens.connector.api.DataSourceField> fields = service.getTypeConfig("mysql");

        assertEquals(6, fields.size());
        assertEquals("host", fields.get(0).getField());
        assertEquals("database", fields.get(2).getField());
    }

    @Test
    void shouldFallBackToBaseTemplateWhenDialectHasNoBuilder() {
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
        when(dialect.getDataSourceConfigBuilder()).thenReturn(null);

        List<com.consilens.connector.api.DataSourceField> fields = service.getTypeConfig("mysql");

        assertEquals(6, fields.size());
        assertEquals("properties", fields.get(5).getField());
        assertEquals(3, fields.get(5).getRows());
    }

    @Test
    void shouldFailGetTypeConfigForUnsupportedType() {
        when(dialectSupport.find("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getTypeConfig("unknown"));
    }

    @Test
    void shouldForwardExtraParamsAsOptionsWhenTesting() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("pg").type("postgresql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":5432,\"database\":\"orders\","
                        + "\"username\":\"admin\",\"password\":\"" + protector.protect("secret") + "\","
                        + "\"schema\":\"public\",\"properties\":\"ssl=true\"}")
                .build()));
        when(connectionTestService.test(any())).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(10L).build());

        service.test(DS_ID);

        ArgumentCaptor<ConnectionTestRequest> captor = ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        ConnectionTestRequest request = captor.getValue();
        assertEquals("postgresql", request.getType());
        assertEquals("10.0.0.5", request.getHost());
        assertEquals("orders", request.getDatabase());
        assertEquals("secret", request.getPassword());
        assertEquals("public", request.getOptions().get("schema"));
        assertEquals("ssl=true", request.getOptions().get("properties"));
        assertTrue(!request.getOptions().containsKey("username"));
        assertTrue(!request.getOptions().containsKey("password"));
        // 透传 options 含 host/port/database（不覆盖 request 字段，由连接层白名单过滤）
        assertEquals("10.0.0.5", request.getOptions().get("host"));
    }

    @Test
    void shouldUseParamSchemaWhenListingTables() throws Exception {
        when(dialectSupport.find("postgresql")).thenReturn(Optional.of(dialect));
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("pg").type("postgresql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":5432,\"database\":\"orders\","
                        + "\"schema\":\"public\"}")
                .build()));
        when(metadataQueryGenerator.getTablesSQL("public")).thenReturn("SELECT table_name FROM ...");
        Connection connection = mockConnection("t1", "t2");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        List<String> tables = service.getTables(DS_ID, "orders");

        assertEquals(List.of("t1", "t2"), tables);
        verify(metadataQueryGenerator).getTablesSQL("public");
    }

    @Test
    void shouldUseParamSchemaWhenListingColumns() throws Exception {
        when(dialectSupport.find("sqlserver")).thenReturn(Optional.of(dialect));
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("sqlserver").type("sqlserver")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":1433,\"database\":\"orders\","
                        + "\"schema\":\"dbo\"}")
                .build()));
        when(metadataQueryGenerator.getTableColumnsSQL("dbo", "t1")).thenReturn("SELECT ...");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("id");
        when(resultSet.getString(2)).thenReturn("bigint");
        when(resultSet.getString(3)).thenReturn("NO");
        when(opener.open(eq(dialect), any())).thenReturn(connection);

        service.getColumns(DS_ID, "orders", "t1");

        verify(metadataQueryGenerator).getTableColumnsSQL("dbo", "t1");
    }

    @Test
    void shouldDecryptPasswordWhenTestingEncryptedDataSource() {
        AesGcmSecretProtector crypto = SecretProtectorTestKeys.protector();
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto,
                mock(DatasourceDefinitionValidator.class), opener);
        String encrypted = crypto.protect("s3cret");
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("secure").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"password\":\"" + encrypted + "\"}")
                .build()));
        when(connectionTestService.test(any())).thenReturn(ConnectionTestResponse.builder()
                .success(true).latencyMs(5L).build());

        encryptedService.test(DS_ID);

        ArgumentCaptor<ConnectionTestRequest> captor = ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        assertEquals("s3cret", captor.getValue().getPassword());
    }

    @Test
    void shouldKeepPasswordWhenUpdatingWithBlankPassword() {
        AesGcmSecretProtector crypto = SecretProtectorTestKeys.protector();
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto,
                mock(DatasourceDefinitionValidator.class), opener);
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("secure").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"password\":\"" + crypto.protect("old-pass") + "\"}")
                .build()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        encryptedService.update(DS_ID, DataSourceCreateRequest.builder()
                .name("secure").type("mysql")
                .param(Map.of("host", "10.0.0.6"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().getParamJson();
        // 保留的旧密码被重新加密存储（v2 前缀且非明文）
        assertTrue(stored.contains("v2:"));
        assertTrue(!stored.contains("old-pass"));
    }

    @Test
    void shouldKeepPasswordWhenUpdatingWithMaskedPassword() {
        AesGcmSecretProtector crypto = SecretProtectorTestKeys.protector();
        DataSourceServiceImpl encryptedService = new DataSourceServiceImpl(repository,
                dialectSupport, connectionTestService, new ObjectMapper(), crypto,
                mock(DatasourceDefinitionValidator.class), opener);
        when(repository.findById(DS_ID)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(DS_ID).name("secure").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"password\":\"" + crypto.protect("old-pass") + "\"}")
                .build()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        encryptedService.update(DS_ID, DataSourceCreateRequest.builder()
                .name("secure").type("mysql")
                .param(Map.of("host", "10.0.0.6", "password", "******"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().getParamJson();
        assertTrue(stored.contains("v2:"));
        assertTrue(!stored.contains("******"), stored);
        assertTrue(!stored.contains("old-pass"), stored);
    }

    @Test
    void protectNeverReturnsPlaintext() {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        String envelope = protector.protect("secret");
        assertTrue(envelope.startsWith("v2:"));
        assertTrue(!envelope.contains("secret"));
    }

    private DataSourceRecord record() {
        return DataSourceRecord.builder()
                .id(DS_ID)
                .name("orders-db")
                .type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"admin\",\"password\":\""
                        + SecretProtectorTestKeys.protector().protect("secret") + "\"}")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private Connection mockConnection(String... values) throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        for (int i = 0; i < values.length; i++) {
            when(resultSet.getString(1)).thenReturn(values[0], values[1]);
        }
        return connection;
    }

    @Test
    void shouldRejectPlaintextSensitivePropertiesOnCreate() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // properties 内明文密码拒绝落库（任何形式的密码都不进系统）
        assertThrows(IllegalArgumentException.class, () -> service.create(
                DataSourceCreateRequest.builder()
                        .name("plaintext-db")
                        .type("mysql")
                        .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders",
                                "properties", "useSSL=false&password=hunter2"))
                        .build()));
    }

    @Test
    void shouldStripMaskedSensitivePropertiesOnSave() {
        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            record.setId(DS_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });

        // 模拟编辑回显后原样提交：properties 中敏感键为掩码占位，不落库
        service.create(DataSourceCreateRequest.builder()
                .name("masked-save-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders",
                        "properties", "useSSL=false&password=******&ApplicationName=consilens"))
                .build());

        DataSourceRecord saved = captor.getValue();
        String paramJson = saved.getParamJson();
        assertTrue(paramJson.contains("useSSL=false"));
        assertTrue(!paramJson.contains("password"));
        assertTrue(paramJson.contains("ApplicationName"));
    }

    @Test
    void shouldStripMaskedSensitivePropertiesOnUpdate() {
        DataSourceRecord existing = DataSourceRecord.builder()
                .id(DS_ID)
                .name("masked-db")
                .type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"properties\":\"useSSL=false&password=legacy-secret&ApplicationName=consilens\"}")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(repository.findById(DS_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 编辑回显的掩码占位不能回填旧明文：properties 是 JDBC 连接属性，
        // 回填会覆盖系统注入的 username/password，且会被敏感键校验拒绝。
        service.update(DS_ID, DataSourceCreateRequest.builder()
                .name("masked-db")
                .type("mysql")
                .param(Map.of("host", "10.0.0.5", "port", 3306, "database", "orders",
                        "properties", "useSSL=false&password=******&ApplicationName=consilens"))
                .build());

        ArgumentCaptor<DataSourceRecord> captor = ArgumentCaptor.forClass(DataSourceRecord.class);
        verify(repository).save(captor.capture());
        String paramJson = captor.getValue().getParamJson();
        assertTrue(paramJson.contains("useSSL=false"), paramJson);
        assertFalse(paramJson.contains("password"), paramJson);
        assertTrue(paramJson.contains("ApplicationName"), paramJson);
    }

    @Test
    void shouldMaskLegacyPlaintextPropertiesInList() throws Exception {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        // 存量数据：paramJson 里 properties 含明文密码
        DataSourceRecord legacy = DataSourceRecord.builder()
                .id(9L)
                .name("legacy-db")
                .type("postgresql")
                .paramJson("{\"host\":\"10.0.0.9\",\"port\":5432,\"database\":\"orders\","
                        + "\"username\":\"app\",\"password\":\"" + protector.protect("real-secret") + "\","
                        + "\"properties\":\"ssl=true&password=plaintext-secret&search_path=public\"}")
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2025-01-01T00:00:00Z"))
                .build();
        when(repository.listAll()).thenReturn(List.of(legacy));

        List<DataSourceDto> dtos = service.list();

        assertEquals(1, dtos.size());
        assertEquals(null, dtos.get(0).getParam().get("password"));
        assertEquals("ssl=true&password=******&search_path=public",
                dtos.get(0).getParam().get("properties"));
    }

    @Test
    void shouldMaskDriverSpecificCredentialPropertiesInList() throws Exception {
        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        DataSourceRecord legacy = DataSourceRecord.builder()
                .id(10L)
                .name("legacy-ssl-db")
                .type("mysql")
                .paramJson("{\"host\":\"10.0.0.10\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"app\",\"password\":\"" + protector.protect("real-secret") + "\","
                        + "\"properties\":\"useSSL=true&sslpassword=ssl-secret&accessToken=token-1&ApplicationName=consilens\"}")
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2025-01-01T00:00:00Z"))
                .build();
        when(repository.listAll()).thenReturn(List.of(legacy));

        List<DataSourceDto> dtos = service.list();

        assertEquals("useSSL=true&sslpassword=******&accessToken=******&ApplicationName=consilens",
                dtos.get(0).getParam().get("properties"));
    }
}
