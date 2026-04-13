package com.consilens.connector.api.model;

import com.consilens.connector.api.enums.DatabaseType;
import lombok.Data;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection pool configuration.
 * 
 * <p>
 * This class encapsulates all configuration parameters for database connection pools.
 * It provides validation and copy functionality to ensure configuration integrity.
 * 
 * @since 1.1.0
 */
@Data
public class PoolConfiguration {
    private String jdbcUrl;
    private String username;
    private String password;
    private DatabaseType databaseType = DatabaseType.UNKNOWN;
    private int maxPoolSize = 10;
    private int minIdle = 2;
    private long connectionTimeout = 30000;
    private long idleTimeout = 600000;
    private long maxLifetime = 1800000;
    private long leakDetectionThreshold = 0;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private boolean useSSL = false;
    private String validationQuery;
    private String connectionInitSql;
    private Duration maxWaitTime = Duration.ofSeconds(30);
    private Map<String, Object> dataSourceProperties = new ConcurrentHashMap<>();

    /**
     * Validate the configuration.
     * 
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("JDBC URL cannot be null or empty");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        // Password can be null or empty string
        // Some databases allow connections without password
        if (maxPoolSize < 1) {
            throw new IllegalArgumentException("Max pool size must be at least 1");
        }
        if (minIdle < 0) {
            throw new IllegalArgumentException("Min idle cannot be negative");
        }
        if (minIdle > maxPoolSize) {
            throw new IllegalArgumentException("Min idle cannot be greater than max pool size");
        }
        if (connectionTimeout <= 0) {
            throw new IllegalArgumentException("Connection timeout must be positive");
        }

        // Auto-detect database type if not set
        if (databaseType == DatabaseType.UNKNOWN) {
            databaseType = DatabaseType.fromJdbcUrl(jdbcUrl);
        }
    }

    /**
     * Create a copy of this configuration.
     * 
     * @return a new PoolConfiguration instance with the same values
     */
    public PoolConfiguration copy() {
        PoolConfiguration copy = new PoolConfiguration();
        copy.setJdbcUrl(this.jdbcUrl);
        copy.setUsername(this.username);
        copy.setPassword(this.password);
        copy.setDatabaseType(this.databaseType);
        copy.setMaxPoolSize(this.maxPoolSize);
        copy.setMinIdle(this.minIdle);
        copy.setConnectionTimeout(this.connectionTimeout);
        copy.setIdleTimeout(this.idleTimeout);
        copy.setMaxLifetime(this.maxLifetime);
        copy.setLeakDetectionThreshold(this.leakDetectionThreshold);
        copy.setAutoCommit(this.autoCommit);
        copy.setReadOnly(this.readOnly);
        copy.setUseSSL(this.useSSL);
        copy.setValidationQuery(this.validationQuery);
        copy.setConnectionInitSql(this.connectionInitSql);
        copy.setMaxWaitTime(this.maxWaitTime);
        copy.setDataSourceProperties(new ConcurrentHashMap<>(this.dataSourceProperties));
        return copy;
    }
}
