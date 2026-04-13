package com.consilens.connector.postgresql;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * PostgreSQL-specific connection pool optimizer.
 * 
 * <p>
 * Provides PostgreSQL-specific optimization properties and default configuration including:
 * <ul>
 * <li>SSL configuration</li>
 * <li>Batch insert rewriting</li>
 * <li>Prepared statement caching</li>
 * </ul>
 * 
 * <p>
 * <b>Note:</b> Binary transfer settings are intentionally omitted to avoid
 * "oid type * not known" errors with certain PostgreSQL configurations.
 * 
 * @since 1.1.0
 */
@Slf4j
public class PostgreSQLConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating PostgreSQL-specific connection pool optimization properties");

        Properties props = new Properties();

        // SSL configuration
        props.setProperty("useSSL", String.valueOf(useSSL));
        props.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        props.setProperty("sslmode", "prefer");

        // Batch operation optimization
        props.setProperty("reWriteBatchedInserts", "true");

        // Prepared statement optimization
        props.setProperty("prepareThreshold", "3");
        props.setProperty("preparedStatementCacheQueries", "256");
        props.setProperty("preparedStatementCacheSizeMiB", "5");

        // Note: binaryTransferEnable with "*" can cause "oid type * not known" errors
        // Only enable for specific types if needed

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating PostgreSQL default connection pool configuration");
        
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
