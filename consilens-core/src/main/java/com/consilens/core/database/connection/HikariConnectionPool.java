package com.consilens.core.database.connection;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.database.dialect.DialectFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Universal connection pool implementation using HikariCP.
 * Works with multiple database types and provides comprehensive monitoring.
 */
@Slf4j
public class HikariConnectionPool implements ConnectionPool {

    private final HikariDataSource dataSource;
    private final PoolConfiguration configuration;
    private final AtomicLong totalCreated = new AtomicLong(0);
    private final AtomicLong totalClosed = new AtomicLong(0);
    private final AtomicLong totalLeaked = new AtomicLong(0);
    private final AtomicLong waitTimeSum = new AtomicLong(0);
    private final AtomicLong waitTimeCount = new AtomicLong(0);
    private final AtomicLong maxWaitTime = new AtomicLong(0);
    private final AtomicLong minWaitTime = new AtomicLong(Long.MAX_VALUE);
    private final Instant createdAt = Instant.now();

    public HikariConnectionPool(PoolConfiguration configuration) {
        this.configuration = configuration.copy();
        this.configuration.validate();


        this.dataSource = createDataSource(this.configuration);

        log.info("Created connection pool for {} with max size: {}, min idle: {}",
                this.configuration.getDatabaseType().getDisplayName(),
                this.configuration.getMaxPoolSize(),
                this.configuration.getMinIdle());
    }

    @Override
    public Connection getConnection() throws SQLException {
        long startTime = System.currentTimeMillis();
        totalCreated.incrementAndGet();
        long waitTime;

        try {
            Connection connection = dataSource.getConnection();

            waitTime = System.currentTimeMillis() - startTime;
            updateWaitTimeStatistics(waitTime);

            log.debug("Connection acquired from pool. Active connections: {}",
                    getActiveConnections());
            return connection;
        } catch (SQLException e) {
            totalCreated.decrementAndGet();
            waitTime = System.currentTimeMillis() - startTime;
            log.error("Failed to acquire connection from pool after {}ms", waitTime, e);
            throw e;
        }
    }

    @Override
    public void releaseConnection(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                totalClosed.incrementAndGet();
                log.debug("Connection released to pool. Active connections: {}",
                        getActiveConnections());
            }
        } catch (SQLException e) {
            log.warn("Error closing connection", e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Connection pool closed for database: {}",
                    configuration.getDatabaseType().getDisplayName());
        }
    }

    @Override
    public int getActiveConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    @Override
    public int getIdleConnections() {
        return dataSource.getHikariPoolMXBean().getIdleConnections();
    }

    @Override
    public int getMaxPoolSize() {
        return dataSource.getMaximumPoolSize();
    }

    @Override
    public int getMinIdleConnections() {
        return dataSource.getMinimumIdle();
    }

    @Override
    public PoolStatistics getStatistics() {
        var poolMXBean = dataSource.getHikariPoolMXBean();

        return new PoolStatistics(
                poolMXBean.getTotalConnections(),
                poolMXBean.getActiveConnections(),
                poolMXBean.getIdleConnections(),
                poolMXBean.getThreadsAwaitingConnection(),
                dataSource.getMaximumPoolSize(),
                dataSource.getMinimumIdle(),
                totalCreated.get(),
                totalClosed.get(),
                totalLeaked.get(),
                waitTimeCount.get() > 0 ? (double) waitTimeSum.get() / waitTimeCount.get() : 0,
                maxWaitTime.get(),
                minWaitTime.get() == Long.MAX_VALUE ? 0 : minWaitTime.get()
        );
    }

    @Override
    public boolean isClosed() {
        return dataSource != null && dataSource.isClosed();
    }

    @Override
    public DatabaseType getDatabaseType() {
        return configuration.getDatabaseType();
    }

    @Override
    public PoolConfiguration getConfiguration() {
        return configuration.copy();
    }

    @Override
    public boolean isHealthy() {
        try {
            if (isClosed()) {
                return false;
            }

            // Try to get a connection and validate it
            try (Connection connection = getConnection()) {
                return connection != null && !connection.isClosed() && connection.isValid(5);
            }
        } catch (SQLException e) {
            log.warn("Pool health check failed", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        PoolStatistics stats = getStatistics();

        metrics.put("databaseType", getDatabaseType().name());
        metrics.put("totalConnections", stats.getTotalConnections());
        metrics.put("activeConnections", stats.getActiveConnections());
        metrics.put("idleConnections", stats.getIdleConnections());
        metrics.put("waitingThreads", stats.getWaitingThreads());
        metrics.put("maxPoolSize", stats.getMaxPoolSize());
        metrics.put("minIdle", stats.getMinIdle());
        metrics.put("totalCreated", stats.getTotalCreated());
        metrics.put("totalClosed", stats.getTotalClosed());
        metrics.put("totalLeaked", stats.getTotalLeaked());
        metrics.put("averageWaitTime", stats.getAverageWaitTime());
        metrics.put("maxWaitTime", stats.getMaxWaitTime());
        metrics.put("minWaitTime", stats.getMinWaitTime());
        metrics.put("utilizationPercentage", stats.getUtilizationPercentage());
        metrics.put("underPressure", stats.isUnderPressure());
        metrics.put("isHealthy", isHealthy());
        metrics.put("isClosed", isClosed());
        metrics.put("uptimeSeconds", java.time.Duration.between(createdAt, Instant.now()).getSeconds());
        metrics.put("jdbcUrl", configuration.getJdbcUrl().replaceAll("password=[^&]*", "password=***"));

        return metrics;
    }

    private HikariDataSource createDataSource(PoolConfiguration config) {
        HikariConfig hikariConfig = new HikariConfig();

        // Basic configuration
        hikariConfig.setDriverClassName(config.getDatabaseType().getDriverClassName());
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());

        // Pool configuration
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());
        hikariConfig.setLeakDetectionThreshold(config.getLeakDetectionThreshold());
        hikariConfig.setAutoCommit(config.isAutoCommit());
        hikariConfig.setReadOnly(config.isReadOnly());

        // Validation
        if (config.getValidationQuery() != null) {
            hikariConfig.setConnectionTestQuery(config.getValidationQuery());
        }

        // Connection initialization SQL (for timezone and other settings)
        if (config.getConnectionInitSql() != null) {
            hikariConfig.setConnectionInitSql(config.getConnectionInitSql());
            log.info("Set connectionInitSql for {}: {}", 
                    config.getDatabaseType().getDisplayName(), 
                    config.getConnectionInitSql());
        }

        // Pool naming for monitoring
        hikariConfig.setPoolName(String.format("HikariPool-%s-%d",
                config.getDatabaseType().name(), System.identityHashCode(this)));

        // Database-specific optimizations using connector API
        applyDatabaseSpecificOptimizations(hikariConfig, config);

        // Custom properties
        config.getDataSourceProperties().forEach(hikariConfig::addDataSourceProperty);

        return new HikariDataSource(hikariConfig);
    }

    private void applyDatabaseSpecificOptimizations(HikariConfig hikariConfig, PoolConfiguration config) {
        try {
            DatabaseDialect dialect = DialectFactory.getDialect(config.getDatabaseType());
            ConnectionPoolOptimizer optimizer = dialect.getConnectionPoolOptimizer();
            Properties optimizations = optimizer.getOptimizationProperties(config.isUseSSL());
            
            // Apply all optimization properties to HikariConfig
            optimizations.forEach((key, value) -> 
                hikariConfig.addDataSourceProperty(key.toString(), value)
            );
            
            log.debug("Applied {} connection pool optimization properties for database type: {}", 
                    optimizations.size(), config.getDatabaseType().getDisplayName());
        } catch (Exception e) {
            log.warn("Failed to apply database-specific optimizations for {}, using defaults: {}", 
                    config.getDatabaseType().getDisplayName(), e.getMessage());
        }
    }

    private void updateWaitTimeStatistics(long waitTime) {
        waitTimeSum.addAndGet(waitTime);
        waitTimeCount.incrementAndGet();

        // Update max wait time
        long currentMax = maxWaitTime.get();
        while (waitTime > currentMax && !maxWaitTime.compareAndSet(currentMax, waitTime)) {
            currentMax = maxWaitTime.get();
        }

        // Update min wait time
        long currentMin = minWaitTime.get();
        while (waitTime < currentMin && !minWaitTime.compareAndSet(currentMin, waitTime)) {
            currentMin = minWaitTime.get();
        }
    }
}