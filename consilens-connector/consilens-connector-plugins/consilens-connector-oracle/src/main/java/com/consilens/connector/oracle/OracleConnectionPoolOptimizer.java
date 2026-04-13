package com.consilens.connector.oracle;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * Oracle-specific connection pool optimizer.
 * 
 * <p>
 * Provides Oracle-specific optimization properties and default configuration including:
 * <ul>
 * <li>Row prefetch optimization</li>
 * <li>NChar handling</li>
 * <li>AutoCommit behavior tuning</li>
 * </ul>
 * 
 * @since 1.1.0
 */
@Slf4j
public class OracleConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating Oracle-specific connection pool optimization properties");

        Properties props = new Properties();

        // Row prefetch optimization
        props.setProperty("oracle.jdbc.defaultRowPrefetch", "1000");

        // NChar handling
        props.setProperty("oracle.jdbc.defaultNChar", "true");
        props.setProperty("oracle.jdbc.ConvertNcharLiterals", "true");

        // AutoCommit optimization
        props.setProperty("oracle.jdbc.autoCommitSpecCompliant", "false");

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating Oracle default connection pool configuration");
        
        PoolConfiguration config = new PoolConfiguration();
        config.setMaxPoolSize(15);
        config.setMinIdle(3);
        config.setMaxLifetime(3600000); // 60 minutes
        config.setIdleTimeout(1800000); // 30 minutes
        config.setConnectionTimeout(60000); // 60 seconds
        config.setLeakDetectionThreshold(120000); // 2 minutes
        config.setValidationQuery("SELECT 1 FROM DUAL");
        
        return config;
    }
}
