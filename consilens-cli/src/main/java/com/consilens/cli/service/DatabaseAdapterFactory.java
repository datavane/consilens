package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.normalization.NormalizationConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.adpter.DefaultDatabaseAdapter;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.database.connection.ConnectionPoolFactory;
import com.consilens.core.database.dialect.DialectFactory;
import com.consilens.core.util.LogUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating DatabaseAdapter instances from CLI configuration.
 */
@Slf4j
public class DatabaseAdapterFactory {

    /**
     * Create a DatabaseAdapter for database 1 from CLI configuration.
     */
    public static DatabaseAdapter createSourceAdapter(CliConfiguration config) throws Exception {
        return createDatabaseAdapter(
                config.getSource().getUrl(),
                config.getSource().getUsername(),
                config.getSource().getPassword(),
                config.getSource().getType(),
                config.getNormalization(),
                config.getAlgorithm(),
                "source");
    }

    /**
     * Create a DatabaseAdapter for database 2 from CLI configuration.
     */
    public static DatabaseAdapter createTargetAdapter(CliConfiguration config) throws Exception {
        return createDatabaseAdapter(
                config.getTarget().getUrl(),
                config.getTarget().getUsername(),
                config.getTarget().getPassword(),
                config.getTarget().getType(),
                config.getNormalization(),
                config.getAlgorithm(),
                "target");
    }

    /**
     * Create a DatabaseAdapter from connection parameters.
     */
    private static DatabaseAdapter createDatabaseAdapter(
            String url,
            String username,
            String password,
            String dbTypeStr,
            NormalizationConfig normalizationConfig,
            String checksumAlgorithm,
            String name) throws Exception {

        LogUtils.logOperationStart("Database adapter creation", "name=" + name, "url=" + url);

        // Detect database type - use explicit type if provided, otherwise auto-detect
        DatabaseType dbType;
        if (dbTypeStr != null && !dbTypeStr.trim().isEmpty()) {
            dbType = DatabaseType.fromString(dbTypeStr);
            if (dbType == DatabaseType.UNKNOWN) {
                log.warn("Unknown database type specified: {}, falling back to auto-detection", dbTypeStr);
                dbType = DatabaseType.fromJdbcUrl(url);
            } else {
                log.info("Using explicitly specified database type: {}", dbType.getDisplayName());
            }
        } else {
            dbType = DatabaseType.fromJdbcUrl(url);
        }

        // Create connection pool using factory
        ConnectionPool connectionPool;
        PoolConfiguration poolConfig = ConnectionPoolFactory.getDefaultConfiguration(dbType);
        connectionPool = ConnectionPoolFactory.createPool(url, username, password, dbType, poolConfig);

        // Merge normalization configuration: database-specific overrides global
        Map<String, ?> mergedConfig = null;
        if (normalizationConfig != null) {
            Map<String, Object> merged = new HashMap<>();
            
            // 1. Add global configuration first
            if (normalizationConfig.getGlobal() != null) {
                merged.putAll(normalizationConfig.getGlobal());
            }
            
            // 2. Add database-specific configuration (overrides global)
            Map<String, TypeNormalizationRule> dbConfig =
                "source".equals(name) ? normalizationConfig.getSource() : normalizationConfig.getTarget();
            if (dbConfig != null) {
                merged.putAll(dbConfig);
            }
            
            mergedConfig = merged.isEmpty() ? null : merged;
        }
        
        // Parse checksum algorithm
        ChecksumAlgorithm checksumAlgorithmEnum = ChecksumAlgorithm.fromString(checksumAlgorithm);
        
        // Create database dialect using factory with merged normalization config
        DatabaseDialect dialect = DialectFactory.getDialect(dbType, mergedConfig);

        // Create and return database adapter with checksum algorithm
        return new DefaultDatabaseAdapter(name, connectionPool, dialect, url, checksumAlgorithmEnum);
    }
}
