package com.consilens.core.database.connection;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.connector.api.enums.DatabaseType;
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
        return createPool(jdbcUrl, username, password, DatabaseType.fromJdbcUrl(jdbcUrl));
    }

    /**
     * Create a connection pool with database type specification.
     */
    public static ConnectionPool createPool(String jdbcUrl, String username, String password, DatabaseType databaseType) {
        return createPool(jdbcUrl, username, password, databaseType, getDefaultConfiguration(databaseType));
    }

    /**
     * Create a connection pool with custom configuration.
     */
    public static ConnectionPool createPool(String jdbcUrl, String username, String password, DatabaseType databaseType, PoolConfiguration configuration) {
        // Use cache if available
        String cacheKey = generateCacheKey(jdbcUrl, username, databaseType);
        ConnectionPool cachedPool = POOL_CACHE.get(cacheKey);
        if (cachedPool != null && !cachedPool.isClosed()) {
            log.debug("Returning cached connection pool for {}", databaseType.getDisplayName());
            return cachedPool;
        }

        // Get default configuration from connector
        PoolConfiguration defaultConfig = getDefaultConfiguration(databaseType);
        
        // Build configuration by merging with defaults
        PoolConfiguration poolConfig = configuration != null ? configuration.copy() : defaultConfig.copy();
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        poolConfig.setDatabaseType(databaseType);
        
        // Preserve connectionInitSql from default config if not set in custom config
        if (poolConfig.getConnectionInitSql() == null && defaultConfig.getConnectionInitSql() != null) {
            poolConfig.setConnectionInitSql(defaultConfig.getConnectionInitSql());
            log.info("Applied default connectionInitSql for {}: {}", 
                     databaseType.getDisplayName(), defaultConfig.getConnectionInitSql());
        } else if (poolConfig.getConnectionInitSql() != null) {
            log.info("Using custom connectionInitSql for {}: {}", 
                     databaseType.getDisplayName(), poolConfig.getConnectionInitSql());
        } else {
            log.debug("No connectionInitSql configured for {}", databaseType.getDisplayName());
        }

        // Create new pool
        HikariConnectionPool pool = new HikariConnectionPool(poolConfig);

        // Cache the pool
        POOL_CACHE.put(cacheKey, pool);

        log.info("Created new connection pool for {} at {}", databaseType.getDisplayName(), jdbcUrl.replaceAll("password=[^&]*", "password=***"));
        return pool;
    }

    /**
     * Get default configuration for a database type from the connector.
     */
    public static PoolConfiguration getDefaultConfiguration(DatabaseType databaseType) {
        try {
            DatabaseDialect dialect = DialectFactory.getDialect(databaseType);
            ConnectionPoolOptimizer optimizer = dialect.getConnectionPoolOptimizer();
            PoolConfiguration config = optimizer.getDefaultConfiguration();
            
            log.debug("Retrieved default configuration from {} connector", databaseType.getDisplayName());
            return config;
        } catch (Exception e) {
            log.warn("Failed to get default configuration from connector for {}, using generic defaults: {}", 
                    databaseType.getDisplayName(), e.getMessage());
            
            // Fallback to generic configuration
            PoolConfiguration config = new PoolConfiguration();
            config.setMaxPoolSize(10);
            config.setMinIdle(2);
            config.setMaxLifetime(1800000); // 30 minutes
            config.setIdleTimeout(600000);  // 10 minutes
            config.setConnectionTimeout(30000); // 30 seconds
            config.setLeakDetectionThreshold(60000); // 1 minute
            config.setValidationQuery("SELECT 1");
            return config;
        }
    }

    /**
     * Create a high-performance configuration for production use.
     */
    public static PoolConfiguration getHighPerformanceConfiguration(DatabaseType databaseType) {
        PoolConfiguration config = getDefaultConfiguration(databaseType);

        // Optimize for high throughput
        config.setMaxPoolSize(Math.min(config.getMaxPoolSize() * 2, 50));
        config.setMinIdle(Math.max(config.getMinIdle() * 2, 5));
        config.setMaxLifetime(config.getMaxLifetime() * 2);
        config.setIdleTimeout(config.getIdleTimeout() * 2);
        config.setLeakDetectionThreshold(0); // Disable leak detection for high performance

        return config;
    }

    /**
     * Create a memory-optimized configuration for low-resource environments.
     */
    public static PoolConfiguration getMemoryOptimizedConfiguration(DatabaseType databaseType) {
        PoolConfiguration config = getDefaultConfiguration(databaseType);

        // Optimize for low memory usage
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
        String cacheKey = generateCacheKey(jdbcUrl, username, DatabaseType.fromJdbcUrl(jdbcUrl));
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
        String cacheKey = generateCacheKey(jdbcUrl, username, DatabaseType.fromJdbcUrl(jdbcUrl));
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

    private static String generateCacheKey(String jdbcUrl, String username, DatabaseType databaseType) {
        return String.format("%s:%s@%s", username, databaseType.name(), jdbcUrl);
    }

    /**
     * Utility method to build JDBC URL.
     */
    public static String buildJdbcUrl(DatabaseType databaseType, String host, int port, String database) {
        switch (databaseType) {
            case MYSQL:
                return String.format("jdbc:mysql://%s:%d/%s", host, port, database);
            case POSTGRESQL:
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case ORACLE:
                return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, database);
            case SQL_SERVER:
                return String.format("jdbc:sqlserver://%s:%d;databaseName=%s", host, port, database);
            case H2:
                return String.format("jdbc:h2:mem:%s", database);
            case SQLITE:
                return String.format("jdbc:sqlite:%s", database);
            default:
                throw new IllegalArgumentException("Unsupported database type: " + databaseType);
        }
    }
}
