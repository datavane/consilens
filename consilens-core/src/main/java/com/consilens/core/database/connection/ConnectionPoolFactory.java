package com.consilens.core.database.connection;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.database.dialect.DialectFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating database connection pools.
 * Provides optimized defaults for different database types by delegating
 * to database-specific connectors.
 */
@Slf4j
public class ConnectionPoolFactory {

    private static final Map<String, ConnectionPool> POOL_CACHE = new ConcurrentHashMap<>();

    /**
     * Create a connection pool with default configuration.
     */
    public static ConnectionPool createPool(String jdbcUrl, String username, String password) {
        String connectorType = DialectFactory.connectorTypeFromJdbcUrl(jdbcUrl);
        return createPool(jdbcUrl, username, password, connectorType);
    }

    /**
     * Create a connection pool with connector type specification.
     */
    public static ConnectionPool createPool(String jdbcUrl, String username, String password, String connectorType) {
        return createPool(jdbcUrl, username, password, connectorType, getDefaultConfiguration(connectorType));
    }

    /**
     * Create a connection pool with custom configuration.
     */
    public static ConnectionPool createPool(String jdbcUrl, String username, String password, String connectorType, PoolConfiguration configuration) {
        String cacheKey = generateCacheKey(jdbcUrl, username, connectorType);
        ConnectionPool cachedPool = POOL_CACHE.get(cacheKey);
        if (cachedPool != null && !cachedPool.isClosed()) {
            log.debug("Returning cached connection pool for {}", connectorType);
            return cachedPool;
        }

        PoolConfiguration defaultConfig = getDefaultConfiguration(connectorType);
        PoolConfiguration poolConfig = configuration != null ? configuration.copy() : defaultConfig.copy();
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        poolConfig.setConnectorType(connectorType);

        if (poolConfig.getConnectionInitSql() == null && defaultConfig.getConnectionInitSql() != null) {
            poolConfig.setConnectionInitSql(defaultConfig.getConnectionInitSql());
            log.info("Applied default connectionInitSql for {}: {}",
                     connectorType, defaultConfig.getConnectionInitSql());
        } else if (poolConfig.getConnectionInitSql() != null) {
            log.info("Using custom connectionInitSql for {}: {}",
                     connectorType, poolConfig.getConnectionInitSql());
        } else {
            log.debug("No connectionInitSql configured for {}", connectorType);
        }

        HikariConnectionPool pool = new HikariConnectionPool(poolConfig);
        POOL_CACHE.put(cacheKey, pool);

        log.info("Created new connection pool for {} at {}", connectorType, jdbcUrl.replaceAll("password=[^&]*", "password=***"));
        return pool;
    }

    /**
     * Get default configuration for a connector type from the dialect.
     */
    public static PoolConfiguration getDefaultConfiguration(String connectorType) {
        try {
            DatabaseDialect dialect = DialectFactory.getDialect(connectorType);
            ConnectionPoolOptimizer optimizer = dialect.getConnectionPoolOptimizer();
            PoolConfiguration config = optimizer.getDefaultConfiguration();
            log.debug("Retrieved default configuration from {} connector", connectorType);
            return config;
        } catch (Exception e) {
            log.warn("Failed to get default configuration from connector for {}, using generic defaults: {}",
                    connectorType, e.getMessage());
            PoolConfiguration config = new PoolConfiguration();
            config.setMaxPoolSize(10);
            config.setMinIdle(2);
            config.setMaxLifetime(1800000);
            config.setIdleTimeout(600000);
            config.setConnectionTimeout(30000);
            config.setLeakDetectionThreshold(60000);
            config.setValidationQuery("SELECT 1");
            return config;
        }
    }

    /**
     * Create a high-performance configuration for production use.
     */
    public static PoolConfiguration getHighPerformanceConfiguration(String connectorType) {
        PoolConfiguration config = getDefaultConfiguration(connectorType);
        config.setMaxPoolSize(Math.min(config.getMaxPoolSize() * 2, 50));
        config.setMinIdle(Math.max(config.getMinIdle() * 2, 5));
        config.setMaxLifetime(config.getMaxLifetime() * 2);
        config.setIdleTimeout(config.getIdleTimeout() * 2);
        config.setLeakDetectionThreshold(0);
        return config;
    }

    /**
     * Create a memory-optimized configuration for low-resource environments.
     */
    public static PoolConfiguration getMemoryOptimizedConfiguration(String connectorType) {
        PoolConfiguration config = getDefaultConfiguration(connectorType);
        config.setMaxPoolSize(Math.max(config.getMaxPoolSize() / 2, 3));
        config.setMinIdle(1);
        config.setMaxLifetime(config.getMaxLifetime() / 2);
        config.setIdleTimeout(config.getIdleTimeout() / 2);
        config.setConnectionTimeout(config.getConnectionTimeout() / 2);
        return config;
    }

    /**
     * Close and remove a cached connection pool.
     */
    public static void closePool(String jdbcUrl, String username) {
        String connectorType = DialectFactory.connectorTypeFromJdbcUrl(jdbcUrl);
        String cacheKey = generateCacheKey(jdbcUrl, username, connectorType);
        ConnectionPool pool = POOL_CACHE.remove(cacheKey);
        if (pool != null) {
            pool.close();
            log.info("Closed and removed cached connection pool for {}",
                    jdbcUrl.replaceAll("password=[^&]*", "password=***"));
        }
    }

    /**
     * Close all cached connection pools.
     */
    public static void closeAllPools() {
        log.info("Closing {} cached connection pools", POOL_CACHE.size());
        POOL_CACHE.values().forEach(ConnectionPool::close);
        POOL_CACHE.clear();
    }

    /**
     * Get statistics for all cached pools.
     */
    public static Map<String, ConnectionPool.PoolStatistics> getAllPoolStatistics() {
        Map<String, ConnectionPool.PoolStatistics> statistics = new ConcurrentHashMap<>();
        POOL_CACHE.forEach((key, pool) -> {
            if (!pool.isClosed()) {
                statistics.put(key, pool.getStatistics());
            }
        });
        return statistics;
    }

    /**
     * Perform health checks on all cached pools.
     */
    public static Map<String, Boolean> checkAllPoolsHealth() {
        Map<String, Boolean> healthStatus = new ConcurrentHashMap<>();
        POOL_CACHE.forEach((key, pool) -> {
            if (!pool.isClosed()) {
                healthStatus.put(key, pool.isHealthy());
            }
        });
        return healthStatus;
    }

    /**
     * Get connection pool from cache.
     */
    public static ConnectionPool getCachedPool(String jdbcUrl, String username) {
        String connectorType = DialectFactory.connectorTypeFromJdbcUrl(jdbcUrl);
        String cacheKey = generateCacheKey(jdbcUrl, username, connectorType);
        return POOL_CACHE.get(cacheKey);
    }

    /**
     * Clear expired or closed pools from cache.
     */
    public static void cleanupCache() {
        POOL_CACHE.entrySet().removeIf(entry -> {
            ConnectionPool pool = entry.getValue();
            boolean shouldRemove = pool.isClosed() || !pool.isHealthy();
            if (shouldRemove) {
                log.info("Removing unhealthy or closed pool from cache: {}", entry.getKey());
                pool.close();
            }
            return shouldRemove;
        });
    }

    /**
     * Get the number of cached pools.
     */
    public static int getCachedPoolCount() {
        return (int) POOL_CACHE.values().stream()
                .filter(pool -> !pool.isClosed())
                .count();
    }

    private static String generateCacheKey(String jdbcUrl, String username, String connectorType) {
        return String.format("%s:%s@%s", username, connectorType, jdbcUrl);
    }
}
