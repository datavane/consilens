package com.consilens.connector.api;

import com.consilens.connector.api.model.PoolConfiguration;

import java.util.Properties;

/**
 * Interface for database-specific connection pool optimizations.
 * 
 * <p>
 * This interface provides methods for generating database-specific optimization
 * properties and default pool configurations. Each database dialect can implement
 * its own optimization strategy based on the database's characteristics and
 * best practices.
 * 
 * <p>
 * <b>Design Note:</b> This interface is part of the dialect system to separate
 * connection pool optimization logic from the core connection pool implementation,
 * allowing each database connector to define its own optimal settings without
 * depending on specific connection pool implementations (e.g., HikariCP).
 * 
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * DatabaseDialect dialect = DialectFactory.getDialect("LMYSQL");
 * ConnectionPoolOptimizer optimizer = dialect.getConnectionPoolOptimizer();
 * 
 * // Get default configuration
 * PoolConfiguration config = optimizer.getDefaultConfiguration();
 * 
 * // Get optimization properties
 * Properties optimizations = optimizer.getOptimizationProperties(true);
 * }</pre>
 * 
 * @since 1.1.0
 * @see DatabaseDialect
 */
public interface ConnectionPoolOptimizer {

    /**
     * Get database-specific optimization properties for connection pool.
     * 
     * <p>
     * This method returns a Properties object containing database-specific
     * settings that should be applied to the connection pool data source,
     * such as:
     * <ul>
     * <li>Prepared statement caching</li>
     * <li>SSL/TLS settings</li>
     * <li>Character encoding</li>
     * <li>Batch operation settings</li>
     * <li>Performance tuning parameters</li>
     * </ul>
     * 
     * @param useSSL whether to enable SSL/TLS connections
     * @return Properties object containing optimization settings
     */
    Properties getOptimizationProperties(boolean useSSL);

    /**
     * Get default connection pool configuration for this database type.
     * 
     * <p>
     * This method returns a PoolConfiguration with database-specific defaults
     * for pool sizing, timeouts, and validation queries. The configuration
     * is optimized for the specific characteristics of the database.
     * 
     * @return default PoolConfiguration for this database type
     */
    PoolConfiguration getDefaultConfiguration();
}
