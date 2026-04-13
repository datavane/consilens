package com.consilens.connector.mysql;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * MySQL-specific connection pool optimizer.
 * 
 * <p>
 * Provides MySQL-specific optimization properties and default configuration including:
 * <ul>
 * <li>Prepared statement caching</li>
 * <li>SSL configuration</li>
 * <li>Character encoding (UTF-8)</li>
 * <li>Batch statement rewriting</li>
 * <li>Server configuration caching</li>
 * </ul>
 * 
 * @since 1.1.0
 */
@Slf4j
public class MySQLConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating MySQL-specific connection pool optimization properties");

        Properties props = new Properties();

        // SSL configuration
        props.setProperty("useSSL", String.valueOf(useSSL));

        // Prepared statement optimization
        props.setProperty("useServerPrepStmts", "true");
        props.setProperty("cachePrepStmts", "true");
        props.setProperty("prepStmtCacheSize", "250");
        props.setProperty("prepStmtCacheSqlLimit", "2048");

        // Performance optimizations
        props.setProperty("useLocalSessionState", "true");
        props.setProperty("rewriteBatchedStatements", "true");
        props.setProperty("cacheResultSetMetadata", "true");
        props.setProperty("cacheServerConfiguration", "true");
        props.setProperty("elideSetAutoCommits", "true");
        props.setProperty("maintainTimeStats", "false");

        // Character encoding
        props.setProperty("serverTimezone", "UTC");
        props.setProperty("useUnicode", "true");
        props.setProperty("characterEncoding", "UTF-8");

        // Authentication
        props.setProperty("allowPublicKeyRetrieval", "true");

        // Session variables for data integrity
        // CRITICAL: Set group_concat_max_len to 1GB to prevent checksum truncation
        // Default is 1024 bytes which causes false positives in diff detection
        props.setProperty("sessionVariables", "group_concat_max_len=1073741824");

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating MySQL default connection pool configuration");
        
        PoolConfiguration config = new PoolConfiguration();
        config.setMaxPoolSize(20);
        config.setMinIdle(5);
        config.setMaxLifetime(1800000); // 30 minutes
        config.setIdleTimeout(600000);  // 10 minutes
        config.setConnectionTimeout(30000); // 30 seconds
        config.setLeakDetectionThreshold(300000); // 5 minutes (increased for large queries)
        config.setValidationQuery("SELECT 1");
        
        return config;
    }
}
