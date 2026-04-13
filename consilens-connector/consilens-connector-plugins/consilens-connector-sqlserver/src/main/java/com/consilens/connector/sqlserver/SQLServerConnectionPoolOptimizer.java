package com.consilens.connector.sqlserver;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * SQL Server-specific connection pool optimizer.
 * 
 * <p>
 * Provides SQL Server-specific optimization properties and default configuration including:
 * <ul>
 * <li>SSL/TLS encryption settings</li>
 * <li>Cursor-based result set handling</li>
 * <li>Response buffering optimization</li>
 * <li>Network packet size tuning</li>
 * </ul>
 * 
 * @since 1.1.0
 */
@Slf4j
public class SQLServerConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating SQL Server-specific connection pool optimization properties");

        Properties props = new Properties();

        // SSL/TLS configuration
        props.setProperty("encrypt", String.valueOf(useSSL));
        props.setProperty("trustServerCertificate", "true");

        // Result set handling
        props.setProperty("selectMethod", "cursor");
        props.setProperty("responseBuffering", "adaptive");

        // Network optimization
        props.setProperty("packetSize", "32767");

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating SQL Server default connection pool configuration");
        
        PoolConfiguration config = new PoolConfiguration();
        config.setMaxPoolSize(20);
        config.setMinIdle(5);
        config.setMaxLifetime(1800000); // 30 minutes
        config.setIdleTimeout(600000);  // 10 minutes
        config.setConnectionTimeout(30000); // 30 seconds
        config.setLeakDetectionThreshold(60000); // 1 minute
        config.setValidationQuery("SELECT 1");
        
        return config;
    }
}
