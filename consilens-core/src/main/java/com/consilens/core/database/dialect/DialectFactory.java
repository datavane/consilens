package com.consilens.core.database.dialect;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.spi.PluginManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory for creating appropriate DatabaseDialect instances based on connector type string.
 */
public final class DialectFactory {

    private static volatile PluginManager<String, DatabaseDialectProvider, DatabaseDialect> manager;

    private DialectFactory() {
    }

    private static void ensureInitialized() {
        if (manager == null) {
            synchronized (DialectFactory.class) {
                if (manager == null) {
                    manager = PluginManager.load(
                            DatabaseDialectProvider.class,
                            DatabaseDialectProvider::getConnectorType,
                            DatabaseDialectProvider::create,
                            DatabaseDialectProvider::create,
                            "DatabaseDialect");
                }
            }
        }
    }

    public static DatabaseDialect getDialect(String connectorType) {
        ensureInitialized();
        return manager.get(connectorType);
    }

    public static DatabaseDialect getDialect(String connectorType, Map<String, ?> normalizationConfig) {
        ensureInitialized();
        return manager.create(connectorType, normalizationConfig);
    }

    public static void clearCache() {
        if (manager != null) {
            manager.clearCache();
        }
    }

    public static int getCacheSize() {
        ensureInitialized();
        return manager.getLoadedKeys().size();
    }

    public static boolean hasDialect(String connectorType) {
        ensureInitialized();
        return manager.supports(connectorType);
    }

    public static Set<String> supportedTypes() {
        ensureInitialized();
        return manager.getSupportedKeys();
    }

    public static Set<String> loadedTypes() {
        ensureInitialized();
        return manager.getLoadedKeys();
    }

    public static List<DatabaseDialect> loadedDialects() {
        ensureInitialized();
        return manager.getLoadedInstances();
    }

    public static DatabaseDialect getLoadedDialect(String connectorType) {
        ensureInitialized();
        return manager.getIfLoaded(connectorType);
    }

    /**
     * Detect connector type from a JDBC URL. Returns lowercase connector type string
     * (e.g. "mysql", "postgresql") or "unknown" if not recognized.
     */
    public static String connectorTypeFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "unknown";
        }
        String lower = jdbcUrl.toLowerCase();
        if (lower.contains("tidb") || (lower.contains("mysql") && lower.contains(":4000"))) {
            return "tidb";
        } else if (lower.contains("doris")) {
            return "doris";
        } else if (lower.contains("starrocks") || (lower.contains("mysql") && lower.contains(":9030"))) {
            return "starrocks";
        } else if (lower.contains("mysql")) {
            return "mysql";
        } else if (lower.contains("postgresql") || lower.contains("postgres")) {
            return "postgresql";
        } else if (lower.contains("oracle")) {
            return "oracle";
        } else if (lower.contains("sqlserver")) {
            return "sqlserver";
        } else if (lower.contains("sqlite")) {
            return "sqlite";
        } else if (lower.contains("h2")) {
            return "h2";
        } else if (lower.contains("snowflake")) {
            return "snowflake";
        } else if (lower.contains("bigquery") || lower.contains("googleapis.com/bigquery")) {
            return "bigquery";
        } else if (lower.contains("redshift") || lower.contains("redshift.amazonaws.com")) {
            return "redshift";
        } else if (lower.contains("databricks")) {
            return "databricks";
        } else if (lower.contains("clickhouse")) {
            return "clickhouse";
        } else if (lower.contains("presto")) {
            return "presto";
        } else if (lower.contains("trino")) {
            return "trino";
        } else if (lower.contains("vertica")) {
            return "vertica";
        } else if (lower.contains("duckdb")) {
            return "duckdb";
        }
        return "unknown";
    }

    static void setManagerForTest(PluginManager<String, DatabaseDialectProvider, DatabaseDialect> testManager) {
        manager = testManager;
    }

    static void reset() {
        manager = null;
    }
}
