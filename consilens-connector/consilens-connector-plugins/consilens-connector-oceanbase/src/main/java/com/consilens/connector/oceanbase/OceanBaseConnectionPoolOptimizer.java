package com.consilens.connector.oceanbase;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * OceanBase-specific connection pool optimizer.
 *
 * <p>
 * Provides OceanBase-specific optimization properties and default configuration.
 * OceanBase in MySQL mode is highly compatible with MySQL, so this optimizer
 * shares much logic with MySQLConnectionPoolOptimizer.
 * </p>
 *
 * <p>
 * Key differences from MySQL:
 * <ul>
 * <li>OceanBase may have different default timezones</li>
 * <li>Connection properties may need adjustment for OceanBase's distributed architecture</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
public class OceanBaseConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating OceanBase-specific connection pool optimization properties");

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
        props.setProperty("useCursorFetch", "true");
        props.setProperty("rewriteBatchedStatements", "true");
        props.setProperty("cacheResultSetMetadata", "true");
        props.setProperty("cacheServerConfiguration", "true");
        props.setProperty("elideSetAutoCommits", "true");
        props.setProperty("maintainTimeStats", "false");

        // Character encoding
        // Keep datetimes as literal wall-clock (see MySQLConnectionPoolOptimizer note).
        props.setProperty("useUnicode", "true");
        props.setProperty("characterEncoding", "UTF-8");

        // Authentication
        props.setProperty("allowPublicKeyRetrieval", "true");

        // Session variables for data integrity
        // CRITICAL: Set group_concat_max_len to 1GB to prevent checksum truncation
        props.setProperty("sessionVariables", "group_concat_max_len=1073741824");

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating OceanBase default connection pool configuration");

        PoolConfiguration config = new PoolConfiguration();
        config.setMaxPoolSize(20);
        config.setMinIdle(5);
        config.setMaxLifetime(1800000); // 30 minutes
        config.setIdleTimeout(600000);  // 10 minutes
        config.setConnectionTimeout(30000); // 30 seconds
        config.setLeakDetectionThreshold(300000); // 5 minutes
        config.setValidationQuery("SELECT 1");

        return config;
    }
}
