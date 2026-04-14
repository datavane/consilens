package com.consilens.core.database.connection;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.PoolConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class ConnectionPoolFactorySQLiteTest {

    @AfterEach
    void tearDown() {
        ConnectionPoolFactory.closeAllPools();
    }

    @Test
    void shouldAllowSqlitePoolConfigurationWithoutUsername() {
        PoolConfiguration config = new PoolConfiguration();
        config.setJdbcUrl("jdbc:sqlite::memory:");
        config.setDatabaseType(DatabaseType.SQLITE);

        assertDoesNotThrow(config::validate);
    }

    @Test
    void shouldStillRequireUsernameForNonSqlitePoolConfiguration() {
        PoolConfiguration config = new PoolConfiguration();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/test");
        config.setDatabaseType(DatabaseType.MYSQL);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Username cannot be null or empty", exception.getMessage());
    }

    @Test
    void shouldAutoDetectSqliteWhenDatabaseTypeIsUnknown() {
        PoolConfiguration config = new PoolConfiguration();
        config.setJdbcUrl("jdbc:sqlite::memory:");
        config.setDatabaseType(DatabaseType.UNKNOWN);

        assertDoesNotThrow(config::validate);
        assertEquals(DatabaseType.SQLITE, config.getDatabaseType());
    }

    @Test
    void shouldNotPushBlankCredentialsIntoSqlitePoolConfiguration() {
        ConnectionPool pool = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:",
                "",
                "",
                DatabaseType.SQLITE,
                null);

        try {
            PoolConfiguration configuration = pool.getConfiguration();
            assertNull(configuration.getUsername());
            assertNull(configuration.getPassword());
            assertEquals(DatabaseType.SQLITE, configuration.getDatabaseType());
        } finally {
            pool.close();
        }
    }

    @Test
    void shouldUseSqliteDefaultsWhenTypeIsUnknown() {
        ConnectionPool pool = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:",
                null,
                null,
                DatabaseType.UNKNOWN,
                null);

        try {
            PoolConfiguration configuration = pool.getConfiguration();
            assertEquals(DatabaseType.SQLITE, configuration.getDatabaseType());
            assertNull(configuration.getUsername());
            assertNull(configuration.getPassword());
            assertEquals(1, configuration.getMaxPoolSize());
        } finally {
            pool.close();
        }
    }

    @Test
    void shouldNormalizeSqliteCacheKeyForBlankUsernames() {
        ConnectionPool poolWithNull = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:",
                null,
                null,
                DatabaseType.UNKNOWN,
                null);

        ConnectionPool poolWithBlank = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:",
                "   ",
                null,
                DatabaseType.UNKNOWN,
                null);

        try {
            assertSame(poolWithNull, poolWithBlank);
            assertSame(poolWithNull, ConnectionPoolFactory.getCachedPool("jdbc:sqlite::memory:", null));
            assertSame(poolWithNull, ConnectionPoolFactory.getCachedPool("jdbc:sqlite::memory:", "   "));
            assertEquals(1, ConnectionPoolFactory.getCachedPoolCount());
        } finally {
            poolWithNull.close();
        }
    }

    @Test
    void shouldProvideReusableSqliteConnectionsWithoutRegisteringMd5Function() throws Exception {
        ConnectionPool pool = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:",
                null,
                null,
                DatabaseType.SQLITE,
                null);

        try {
            try (Connection firstConnection = pool.getConnection();
                 Statement statement = firstConnection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertEquals(1, resultSet.getInt(1));
            }

            try (Connection secondConnection = pool.getConnection();
                 Statement statement = secondConnection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertEquals(1, resultSet.getInt(1));
            }

            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThrows(Exception.class, () -> {
                    try (ResultSet ignored = statement.executeQuery("SELECT md5('abc')")) {
                        fail("Expected SQLite md5 function to be unavailable");
                    }
                });
            }
        } finally {
            pool.close();
        }
    }
}
