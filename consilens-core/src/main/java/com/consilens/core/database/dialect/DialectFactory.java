package com.consilens.core.database.dialect;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.spi.PluginManager;
import com.consilens.spi.ServiceLoaderUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Factory for creating appropriate DatabaseDialect instances based on connector type string.
 */
public final class DialectFactory {

    private static volatile PluginManager<String, DatabaseDialectProvider, DatabaseDialect> manager;
    private static volatile List<DatabaseDialectProvider> urlProviders;

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
    /**
     * URL schemes for connector types that have no dialect provider in this
     * build. Priorities preserve the legacy branch order; the resulting
     * connector type identifiers are unchanged.
     */
    private static final List<LegacyUrlMatcher> LEGACY_URL_MATCHERS = List.of(
            new LegacyUrlMatcher(80, "sqlite", "sqlite"),
            new LegacyUrlMatcher(90, "h2", "h2"),
            new LegacyUrlMatcher(100, "snowflake", "snowflake"),
            new LegacyUrlMatcher(110, "bigquery", "bigquery", "googleapis.com/bigquery"),
            new LegacyUrlMatcher(120, "redshift", "redshift", "redshift.amazonaws.com"),
            new LegacyUrlMatcher(130, "databricks", "databricks"),
            new LegacyUrlMatcher(170, "vertica", "vertica"),
            new LegacyUrlMatcher(180, "duckdb", "duckdb"));

    public static String connectorTypeFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "unknown";
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        int bestPriority = Integer.MAX_VALUE;
        String connectorType = "unknown";
        for (DatabaseDialectProvider provider : urlProviders()) {
            int priority = provider.getJdbcUrlMatchPriority();
            if (priority < bestPriority && provider.supportsJdbcUrl(lower)) {
                bestPriority = priority;
                connectorType = provider.getConnectorType();
            }
        }
        for (LegacyUrlMatcher matcher : LEGACY_URL_MATCHERS) {
            if (matcher.priority < bestPriority && matcher.matches(lower)) {
                bestPriority = matcher.priority;
                connectorType = matcher.connectorType;
            }
        }
        return connectorType;
    }

    private static List<DatabaseDialectProvider> urlProviders() {
        List<DatabaseDialectProvider> providers = urlProviders;
        if (providers == null) {
            synchronized (DialectFactory.class) {
                providers = urlProviders;
                if (providers == null) {
                    providers = ServiceLoaderUtils.loadAll(DatabaseDialectProvider.class);
                    urlProviders = providers;
                }
            }
        }
        return providers;
    }

    private static final class LegacyUrlMatcher {
        private final int priority;
        private final String connectorType;
        private final String[] tokens;

        private LegacyUrlMatcher(int priority, String connectorType, String... tokens) {
            this.priority = priority;
            this.connectorType = connectorType;
            this.tokens = tokens;
        }

        private boolean matches(String lowerJdbcUrl) {
            for (String token : tokens) {
                if (lowerJdbcUrl.contains(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    static void setManagerForTest(PluginManager<String, DatabaseDialectProvider, DatabaseDialect> testManager) {
        manager = testManager;
    }

    static void reset() {
        manager = null;
        urlProviders = null;
    }
}
