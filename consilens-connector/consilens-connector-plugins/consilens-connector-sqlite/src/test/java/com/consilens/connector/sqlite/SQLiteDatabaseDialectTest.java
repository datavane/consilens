package com.consilens.connector.sqlite;

import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteDatabaseDialectTest {

    @Test
    void shouldExposeSqliteDatabaseTypeAndValidationComponents() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        assertEquals(DatabaseType.SQLITE, dialect.getDatabaseType());
        assertNotNull(dialect.getConnectionPoolOptimizer());
        assertNotNull(dialect.getCapabilityProvider());
        assertNotNull(dialect.getMetadataQueryGenerator());
        assertEquals("SELECT 1", dialect.getMetadataQueryGenerator().getHealthCheckSQL());
        assertEquals("SELECT sqlite_version() AS version", dialect.getMetadataQueryGenerator().getDatabaseMetadataSQL());
    }

    @Test
    void shouldLoadProviderViaServiceLoader() {
        ServiceLoader<DatabaseDialectProvider> loader = ServiceLoader.load(DatabaseDialectProvider.class);

        boolean found = false;
        for (DatabaseDialectProvider provider : loader) {
            if (provider.getDatabaseType() == DatabaseType.SQLITE) {
                DatabaseDialect dialect = provider.create();
                assertEquals(DatabaseType.SQLITE, dialect.getDatabaseType());
                found = true;
                break;
            }
        }

        assertTrue(found, "Expected SQLite provider to be discoverable via ServiceLoader");
    }
}
