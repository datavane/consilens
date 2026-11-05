package com.consilens.server.application.connection;

import com.consilens.connector.api.DatabaseDialect;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Shared JDBC wiring for connection tests and metadata exploration.
 *
 * <p>The JDBC URL template, driver class name and default port are all provided
 * by the connector SPI ({@link DatabaseDialect}) instead of being hard-coded
 * in the server.
 */
public final class JdbcConnectionSupport {

    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    private JdbcConnectionSupport() {
    }

    /**
     * Resolves and validates a target host against SSRF rules: loopback,
     * link-local (incl. cloud metadata 169.254.169.254), multicast, unspecified
     * and broadcast addresses are rejected. Private RFC1918 ranges stay allowed
     * because enterprise data sources commonly live on internal networks.
     *
     * @return the first verified address, to be used as the connection target
     */
    /**
     * Resolves a target host to an IP literal for the JDBC URL. No address
     * filtering is applied (loopback / link-local / any address are all
     * allowed), matching the datavines connector behavior. The host is
     * resolved once and the verified address is used for the connection so
     * the DNS answer cannot change between validation and connect.
     *
     * @return the first resolved address, to be used as the connection target
     */
    public static InetAddress validateTargetHost(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        return addresses[0];
    }

    /**
     * IPv6 literals must be bracketed inside JDBC URLs: jdbc:mysql://[::1]:3306/db.
     */
    public static String formatHost(String address) {
        return address.contains(":") ? "[" + address + "]" : address;
    }

    /**
     * Builds the connection params for {@link DatabaseDialect#buildJdbcUrl(Map)}
     * and pre-loads the driver class.
     *
     * @param dialect      connector dialect (from SPI)
     * @param hostLiteral  verified IP literal (see {@link #validateTargetHost})
     * @param port         explicit port or null to use the dialect default
     * @param database     database / schema / service name or null
     * @return JDBC URL built by the dialect
     */
    public static String buildJdbcUrl(DatabaseDialect dialect,
                                      String hostLiteral,
                                      Integer port,
                                      String database) {
        return buildJdbcUrl(dialect, hostLiteral, port, database, null);
    }

    /**
     * Builds the connection params for {@link DatabaseDialect#buildJdbcUrl(Map)}
     * and pre-loads the driver class.
     *
     * @param dialect      connector dialect (from SPI)
     * @param hostLiteral  verified IP literal (see {@link #validateTargetHost})
     * @param port         explicit port or null to use the dialect default
     * @param database     database / schema / service name or null
     * @param extraParams  extra dialect-specific params (e.g. sid/schema/properties)
     *                     merged into the params map; never overrides
     *                     host/port/database already set above
     * @return JDBC URL built by the dialect
     */
    public static String buildJdbcUrl(DatabaseDialect dialect,
                                      String hostLiteral,
                                      Integer port,
                                      String database,
                                      Map<String, Object> extraParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("host", hostLiteral);
        if (port != null) {
            params.put("port", port);
        }
        if (database != null && !database.isBlank()) {
            params.put("database", database);
        }
        if (extraParams != null) {
            for (Map.Entry<String, Object> entry : extraParams.entrySet()) {
                if (entry.getValue() != null && !params.containsKey(entry.getKey())) {
                    params.put(entry.getKey(), entry.getValue());
                }
            }
        }
        String url = dialect.buildJdbcUrl(params);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("unsupported datasource type: " + dialect.getConnectorType());
        }
        return url;
    }

    /**
     * Opens a JDBC connection through the dialect-provided driver and URL.
     * The host must already be validated (use {@link #validateTargetHost}).
     */
    public static java.sql.Connection open(DatabaseDialect dialect,
                                           String hostLiteral,
                                           Integer port,
                                           String database,
                                           String username,
                                           String password,
                                           int timeoutSeconds) throws Exception {
        return open(dialect, hostLiteral, port, database, username, password, timeoutSeconds, null);
    }

    /**
     * Opens a JDBC connection through the dialect-provided driver and URL.
     * The host must already be validated (use {@link #validateTargetHost}).
     * Extra params (e.g. sid/schema) are merged into the URL params map;
     * a {@code properties} entry (key=value&key2=value2) is applied as
     * JDBC connection properties and never part of the URL.
     */
    public static java.sql.Connection open(DatabaseDialect dialect,
                                           String hostLiteral,
                                           Integer port,
                                           String database,
                                           String username,
                                           String password,
                                           int timeoutSeconds,
                                           Map<String, Object> extraParams) throws Exception {
        String driverClass = dialect.getJdbcDriverClassName();
        if (driverClass == null || driverClass.isBlank()) {
            throw new IllegalArgumentException("unsupported datasource type: " + dialect.getConnectorType());
        }
        Class.forName(driverClass);
        String url = buildJdbcUrl(dialect, hostLiteral, port, database, extraParams);
        Properties properties = connectionProperties(username, password, extraParams);
        DriverManager.setLoginTimeout(timeoutSeconds);
        return DriverManager.getConnection(url, properties);
    }

    /**
     * 连接属性只包含身份与用户显式配置；connectTimeout/loginTimeout 由
     * DriverManager.setLoginTimeout 统一处理，避免严格驱动拒绝未知键。
     */
    static Properties connectionProperties(String username, String password,
                                           Map<String, Object> extraParams) {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        if (extraParams != null) {
            applyJdbcProperties(properties, extraParams.get("properties"));
        }
        return properties;
    }

    /** 解析 key=value&key2=value2 形式的连接属性并注入 Properties(不进 URL)。
     *  敏感键(user/password/超时)一律剥离,身份与超时由系统接管。 */
    static void applyJdbcProperties(Properties properties, Object raw) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw);
        for (String pair : text.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (DatasourceConnectionPolicy.isSensitivePropertyKey(key)) {
                continue;
            }
            properties.setProperty(key, value);
        }
    }

    /**
     * Sanitizes an exception message so credentials never leak:
     * strips user:pass@ fragments from URLs and user names echoed back by
     * JDBC drivers (single or double quoted).
     */
    private static final Pattern JDBC_USER_INFO = Pattern.compile("(?<=://)[^/@\\s]+@");
    private static final Pattern ORACLE_THIN_CREDENTIALS =
            Pattern.compile("(?i)(jdbc:oracle:thin:)[^/@\\s]+/[^@\\s]+@");
    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "(?i)([?&;][^=&#;\\s]*(?:password|passwd|pwd|secret|token|credential|apikey|api_key|user)[^=&#;\\s]*=)[^&;\\s]*");

    public static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return maskMessage(message);
    }

    /** Visible for tests: mask credentials embedded in JDBC URLs / driver messages. */
    public static String maskMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String masked = ORACLE_THIN_CREDENTIALS.matcher(message).replaceAll("$1***/***@");
        masked = JDBC_USER_INFO.matcher(masked).replaceAll("***@");
        masked = SENSITIVE_QUERY_PARAM.matcher(masked).replaceAll("$1***");
        return masked.replaceAll("(?i)for user ['\"][^'\"]*['\"]", "for user '***'");
    }
}
