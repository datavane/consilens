package com.consilens.server.application.connection;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.datasource.DialectSupport;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.AesGcmSecretProtector;
import com.consilens.server.support.crypto.SecretProtectorTestKeys;
import com.consilens.connector.api.DatabaseDialect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConnectionTestServiceTest {

    private DialectSupport dialectSupport;
    private DatabaseDialect dialect;
    private ConnectionOpener opener;
    private ConnectionTestServiceImpl service;

    @BeforeEach
    void setUp() {
        dialectSupport = mock(DialectSupport.class);
        dialect = mock(DatabaseDialect.class);
        opener = mock(ConnectionOpener.class);
        service = new ConnectionTestServiceImpl(opener, dialectSupport);
        when(dialect.getConnectorType()).thenReturn("mysql");
        when(dialect.getJdbcDriverClassName()).thenReturn("com.mysql.cj.jdbc.Driver");
        when(dialectSupport.find("mysql")).thenReturn(Optional.of(dialect));
        when(dialect.buildJdbcUrl(any())).thenReturn("jdbc:mysql://8.8.8.8:3306/db");
    }

    @Test
    void shouldFallBackToStoredPasswordForEditFormTests() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        when(opener.open(any(), properties.capture())).thenReturn(connection);

        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        DataSourceRepository repository = mock(DataSourceRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(7L)
                .name("orders")
                .type("mysql")
                .paramJson("{\"password\":\"" + protector.protect("stored-secret") + "\"}")
                .build()));
        ConnectionTestServiceImpl editService = new ConnectionTestServiceImpl(opener, dialectSupport,
                repository, protector, new ObjectMapper());

        ConnectionTestResponse response = editService.test(ConnectionTestRequest.builder()
                .type("mysql").host("8.8.8.8").database("db").username("u")
                .datasourceId(7L)
                .build());

        assertTrue(response.isSuccess());
        assertEquals("stored-secret", properties.getValue().getProperty("password"));
    }

    @Test
    void shouldFallBackToStoredPasswordWhenEditFormReturnsMask() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        when(opener.open(any(), properties.capture())).thenReturn(connection);

        AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
        DataSourceRepository repository = mock(DataSourceRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.of(DataSourceRecord.builder()
                .id(7L)
                .name("orders")
                .type("mysql")
                .paramJson("{\"password\":\"" + protector.protect("stored-secret") + "\"}")
                .build()));
        ConnectionTestServiceImpl editService = new ConnectionTestServiceImpl(opener, dialectSupport,
                repository, protector, new ObjectMapper());

        ConnectionTestResponse response = editService.test(ConnectionTestRequest.builder()
                .type("mysql").host("8.8.8.8").database("db").username("u")
                .password("******")
                .datasourceId(7L)
                .build());

        assertTrue(response.isSuccess());
        assertEquals("stored-secret", properties.getValue().getProperty("password"));
    }

    @Test
    void shouldSucceedWhenDialectProvidesUrlAndConnectionWorks() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        ConnectionTestResponse response = service.test(request("mysql", "8.8.8.8", null, "db", "u", "p"));

        assertTrue(response.isSuccess());
        assertNull(response.getError());
        verify(dialect).buildJdbcUrl(any());
    }

    @Test
    void shouldApplyConfiguredJdbcPropertiesToConnectionTest() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
        when(opener.open(any(), properties.capture())).thenReturn(connection);

        ConnectionTestResponse response = service.test(ConnectionTestRequest.builder()
                .type("mysql").host("8.8.8.8").database("db")
                .options(Map.of("properties", "useSSL=false&socketTimeout=1000"))
                .build());

        assertTrue(response.isSuccess());
        assertEquals("false", properties.getValue().getProperty("useSSL"));
        assertEquals("1000", properties.getValue().getProperty("socketTimeout"));
        assertFalse(properties.getValue().containsKey("connectTimeout"));
    }

    @Test
    void shouldUseVerifiedIpLiteralForUrl() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        service.test(request("mysql", "8.8.8.8", null, "db", null, null));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dialect).buildJdbcUrl(paramsCaptor.capture());
        assertEquals("8.8.8.8", paramsCaptor.getValue().get("host"));
        assertFalse(paramsCaptor.getValue().containsKey("port"));
        assertEquals("db", paramsCaptor.getValue().get("database"));
    }

    @Test
    void shouldPassExplicitPortToDialect() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        service.test(request("mysql", "8.8.8.8", 3307, "db", null, null));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dialect).buildJdbcUrl(paramsCaptor.capture());
        assertEquals(3307, paramsCaptor.getValue().get("port"));
    }

    @Test
    void shouldRejectDatabaseUrlInjectionForDirectServiceCallers() throws Exception {
        ConnectionTestResponse response = service.test(
                request("mysql", "8.8.8.8", null, "db?allowLoadLocalInfile=true", null, null));

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("database"), response.getError());
        verify(opener, never()).open(any(), any());
    }

    @Test
    void shouldFailForUnknownType() throws Exception {
        when(dialectSupport.find("unknown")).thenReturn(Optional.empty());

        ConnectionTestResponse response = service.test(request("unknown", "8.8.8.8", null, null, null, null));

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("unsupported datasource type: unknown"));
        verify(opener, never()).open(any(), any());
    }

    @Test
    void shouldFailWhenDialectBuildsNoUrl() {
        when(dialect.buildJdbcUrl(any())).thenReturn(null);

        ConnectionTestResponse response = service.test(request("mysql", "8.8.8.8", null, null, null, null));

        assertFalse(response.isSuccess());
        assertTrue(response.getError().contains("unsupported datasource type"));
    }

    @Test
    void shouldAllowLoopbackHost() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        ConnectionTestResponse response = service.test(request("mysql", "127.0.0.1", null, null, null, null));

        assertTrue(response.isSuccess());
        assertNull(response.getError());
    }

    @Test
    void shouldAllowLocalhostHostname() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        ConnectionTestResponse response = service.test(request("mysql", "localhost", null, null, null, null));

        assertTrue(response.isSuccess());
        assertNull(response.getError());
    }

    @Test
    void shouldAllowLinkLocalHost() throws Exception {
        // 与 datavines 一致：不做地址过滤，169.254.x.x 也可连接
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        ConnectionTestResponse response = service.test(request("mysql", "169.254.169.254", null, null, null, null));

        assertTrue(response.isSuccess());
        assertNull(response.getError());
    }

    @Test
    void shouldDesensitizeUserInError() throws Exception {
        when(opener.open(any(), any()))
                .thenThrow(new SQLException("Access denied for user 'admin'@'8.8.8.8' (using password: YES)"));

        ConnectionTestResponse response = service.test(request("mysql", "8.8.8.8", null, null, "admin", "secret"));

        assertFalse(response.isSuccess());
        assertFalse(response.getError().contains("admin"));
        assertTrue(response.getError().contains("for user '***'"));
    }

    @Test
    void shouldStripUserPassFromUrlInError() throws Exception {
        when(opener.open(any(), any()))
                .thenThrow(new SQLException("Communications link failure at jdbc:mysql://admin:secret@8.8.8.8:3306/db"));

        ConnectionTestResponse response = service.test(request("mysql", "8.8.8.8", null, "db", "admin", "secret"));

        assertFalse(response.isSuccess());
        assertFalse(response.getError().contains("secret"));
        assertTrue(response.getError().contains("***@"));
    }

    @Test
    void shouldPassCredentialsToOpener() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        service.test(request("mysql", "8.8.8.8", null, "db", "admin", "secret"));

        ArgumentCaptor<Properties> propertiesCaptor = ArgumentCaptor.forClass(Properties.class);
        verify(opener).open(any(), propertiesCaptor.capture());
        assertEquals("admin", propertiesCaptor.getValue().getProperty("user"));
        assertEquals("secret", propertiesCaptor.getValue().getProperty("password"));
    }

    @Test
    void shouldForwardWhitelistedOptionsToDialectUrlParams() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);
        when(dialect.getConnectorType()).thenReturn("oracle");
        when(dialectSupport.find("oracle")).thenReturn(Optional.of(dialect));

        service.test(ConnectionTestRequest.builder()
                .type("oracle")
                .host("8.8.8.8")
                .port(1521)
                .database("ORCL")
                .username("admin")
                .password("secret")
                .options(java.util.Map.of(
                        "sid", "ORCL",
                        "schema", "public",
                        "properties", "useSSL=false",
                        "evil", "jdbc:oracle:thin:@//attacker",
                        "host", "6.6.6.6"))
                .build());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dialect).buildJdbcUrl(paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        // 白名单键透传
        assertEquals("ORCL", params.get("sid"));
        assertEquals("public", params.get("schema"));
        assertEquals("useSSL=false", params.get("properties"));
        // 非白名单键被丢弃
        assertFalse(params.containsKey("evil"));
        // 已有值不被 options 覆盖
        assertEquals("8.8.8.8", params.get("host"));
        assertEquals(1521, params.get("port"));
        assertEquals("ORCL", params.get("database"));
    }

    @Test
    void shouldIgnoreOptionsWhenAbsentOrEmpty() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(opener.open(any(), any())).thenReturn(connection);

        service.test(request("mysql", "8.8.8.8", null, "db", null, null)
                .toBuilder().options(java.util.Map.of()).build());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dialect).buildJdbcUrl(paramsCaptor.capture());
        assertFalse(paramsCaptor.getValue().containsKey("schema"));
    }

    private ConnectionTestRequest request(String type, String host, Integer port,
                                          String database, String username, String password) {
        return ConnectionTestRequest.builder()
                .type(type)
                .host(host)
                .port(port)
                .database(database)
                .username(username)
                .password(password)
                .build();
    }
}
