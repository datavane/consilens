package com.consilens.core.database.dialect;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.spi.PluginManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory for creating appropriate DatabaseDialect instances based on DatabaseType.
 */
public final class DialectFactory {

    private static volatile PluginManager<DatabaseType, DatabaseDialectProvider, DatabaseDialect> manager;

    private DialectFactory() {
    }

    private static void ensureInitialized() {
        if (manager == null) {
            synchronized (DialectFactory.class) {
                if (manager == null) {
                    manager = PluginManager.load(
                            DatabaseDialectProvider.class,
                            DatabaseDialectProvider::getDatabaseType,
                            DatabaseDialectProvider::create,
                            DatabaseDialectProvider::create,
                            "DatabaseDialect");
                }
            }
        }
    }

    public static DatabaseDialect getDialect(DatabaseType databaseType) {
        ensureInitialized();
        return manager.get(databaseType);
    }

    public static DatabaseDialect getDialect(DatabaseType databaseType, Map<String, ?> normalizationConfig) {
        ensureInitialized();
        return manager.create(databaseType, normalizationConfig);
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

    public static boolean hasDialect(DatabaseType databaseType) {
        ensureInitialized();
        return manager.supports(databaseType);
    }

    public static Set<DatabaseType> supportedTypes() {
        ensureInitialized();
        return manager.getSupportedKeys();
    }

    public static Set<DatabaseType> loadedTypes() {
        ensureInitialized();
        return manager.getLoadedKeys();
    }

    public static List<DatabaseDialect> loadedDialects() {
        ensureInitialized();
        return manager.getLoadedInstances();
    }

    public static DatabaseDialect getLoadedDialect(DatabaseType databaseType) {
        ensureInitialized();
        return manager.getIfLoaded(databaseType);
    }

    static void setManagerForTest(PluginManager<DatabaseType, DatabaseDialectProvider, DatabaseDialect> testManager) {
        manager = testManager;
    }

    static void reset() {
        manager = null;
    }
}
