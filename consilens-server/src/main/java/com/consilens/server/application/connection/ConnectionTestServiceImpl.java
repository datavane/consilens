package com.consilens.server.application.connection;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.datasource.DialectSupport;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.support.crypto.SecretProtector;
import com.consilens.connector.api.DatabaseDialect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

/**
 * Connection test backed by the connector SPI: the driver class, JDBC URL
 * template and default port all come from {@link DatabaseDialect} (via
 * {@link DialectSupport}) instead of being hard-coded here.
 *
 * <p>The host is resolved once and the verified IP literal is used for the
 * actual connection (no address filtering; see
 * {@link JdbcConnectionSupport#validateTargetHost}).
 */
@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final int CONNECT_TIMEOUT_SECONDS = JdbcConnectionSupport.DEFAULT_CONNECT_TIMEOUT_SECONDS;

    private final ConnectionOpener connectionOpener;
    private final DialectSupport dialectSupport;
    private final DataSourceRepository dataSourceRepository;
    private final SecretProtector secretProtector;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConnectionTestServiceImpl(DialectSupport dialectSupport,
                                     DataSourceRepository dataSourceRepository,
                                     SecretProtector secretProtector,
                                     ObjectMapper objectMapper) {
        this(ConnectionTestServiceImpl::openConnection, dialectSupport,
                dataSourceRepository, secretProtector, objectMapper);
    }

    ConnectionTestServiceImpl(ConnectionOpener connectionOpener, DialectSupport dialectSupport) {
        this(connectionOpener, dialectSupport, null, null, null);
    }

    ConnectionTestServiceImpl(ConnectionOpener connectionOpener,
                              DialectSupport dialectSupport,
                              DataSourceRepository dataSourceRepository,
                              SecretProtector secretProtector,
                              ObjectMapper objectMapper) {
        this.connectionOpener = connectionOpener;
        this.dialectSupport = dialectSupport;
        this.dataSourceRepository = dataSourceRepository;
        this.secretProtector = secretProtector;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConnectionTestResponse test(ConnectionTestRequest request) {
        long startedAt = System.currentTimeMillis();
        String url;
        Properties properties;
        try {
            DatabaseDialect dialect = dialectSupport.find(request.getType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unsupported datasource type: " + request.getType()));
            Map<String, Object> options = whitelistedOptions(request.getOptions());
            // 直接调用（AI probe 等）不经过 Web 层 @Valid，必须在 service 内复用同一套
            // 字符白名单，避免 database 注入 JDBC URL 参数。
            Map<String, Object> connectionParams = new java.util.LinkedHashMap<>();
            connectionParams.put("host", request.getHost());
            if (request.getPort() != null) {
                connectionParams.put("port", request.getPort());
            }
            if (request.getDatabase() != null && !request.getDatabase().isBlank()) {
                connectionParams.put("database", request.getDatabase());
            }
            if (options != null) {
                connectionParams.putAll(options);
            }
            DatasourceConnectionPolicy.validateParam(connectionParams);
            // 校验与建连使用同一解析结果（IP 字面量），避免 DNS rebinding TOCTOU
            InetAddress target = JdbcConnectionSupport.validateTargetHost(request.getHost());
            url = JdbcConnectionSupport.buildJdbcUrl(
                    dialect,
                    JdbcConnectionSupport.formatHost(target.getHostAddress()),
                    request.getPort(),
                    request.getDatabase(),
                    options);
            properties = buildProperties(request.getUsername(), resolvePassword(request));
            JdbcConnectionSupport.applyJdbcProperties(properties,
                    options == null ? null : options.get("properties"));
        } catch (Exception exception) {
            return failure(startedAt, exception);
        }
        try (Connection connection = connectionOpener.open(url, properties)) {
            if (connection == null || connection.isClosed()) {
                return failure(startedAt, "connection returned no usable session");
            }
            return ConnectionTestResponse.builder()
                    .success(true)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .error(null)
                    .build();
        } catch (Exception exception) {
            return failure(startedAt, exception);
        }
    }

    /**
     * 编辑态表单不回显密码：显式密码优先，留空时回填该数据源已保存的密码，
     * 这样“测试连接”测的是当前表单独连接参数，而不是已保存的旧连接。
     */
    private String resolvePassword(ConnectionTestRequest request) {
        String password = request.getPassword();
        // 编辑态回显的 ****** 不是真实密码：与留空一样回填已保存密码
        if (password != null && !password.isBlank() && !password.matches("\\*+")) {
            return password;
        }
        if (request.getDatasourceId() == null || dataSourceRepository == null
                || secretProtector == null || objectMapper == null) {
            return null;
        }
        return dataSourceRepository.findById(request.getDatasourceId())
                .map(DataSourceRecord::getParamJson)
                .map(this::decryptStoredPassword)
                .orElse(null);
    }

    private String decryptStoredPassword(String paramJson) {
        try {
            Map<?, ?> param = objectMapper.readValue(paramJson, Map.class);
            Object stored = param.get("password");
            return stored == null ? null : secretProtector.reveal(String.valueOf(stored));
        } catch (Exception exception) {
            // 历史明文/换 key 等情况按无密码处理，由驱动返回真实的认证失败
            return null;
        }
    }

    private static Properties buildProperties(String username, String password) {        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        // timeout is enforced via DriverManager.setLoginTimeout below; passing
        // connectTimeout/loginTimeout properties breaks strict drivers (e.g.
        // clickhouse-jdbc rejects unknown config keys)
        return properties;
    }

    private static Connection openConnection(String url, Properties properties) throws java.sql.SQLException {
        java.sql.DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
        return java.sql.DriverManager.getConnection(url, properties);
    }

    private static java.util.Map<String, Object> whitelistedOptions(java.util.Map<String, Object> options) {
        return DatasourceConnectionPolicy.whitelistedOptions(options);
    }

    private ConnectionTestResponse failure(long startedAt, String message) {
        return failure(startedAt, message, null);
    }

    /** 失败响应保留原始异常链，供 AI 工具按 SQLState 区分认证与网络错误。 */
    private ConnectionTestResponse failure(long startedAt, Exception exception) {
        return failure(startedAt, JdbcConnectionSupport.safeMessage(exception), exception);
    }

    private ConnectionTestResponse failure(long startedAt, String message, Throwable cause) {
        return ConnectionTestResponse.builder()
                .success(false)
                .latencyMs(System.currentTimeMillis() - startedAt)
                .error(message)
                .cause(cause)
                .build();
    }
}
