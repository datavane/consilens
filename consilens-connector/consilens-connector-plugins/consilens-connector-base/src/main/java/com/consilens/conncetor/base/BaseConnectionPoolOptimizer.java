package com.consilens.conncetor.base;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * Base implementation of connection pool optimizer.
 * 
 * <p>
 * This class provides a default implementation that returns empty properties
 * and generic configuration. It can be used for databases that don't require
 * specific optimizations, or as a base class for database-specific optimizers.
 * 
 * @since 1.1.0
 */
@Slf4j
public class BaseConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Using default connection pool optimizer (no specific optimizations)");
        return new Properties();
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Using generic default connection pool configuration");
        
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
