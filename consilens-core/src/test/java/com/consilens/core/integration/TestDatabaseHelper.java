package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.adpter.DefaultDatabaseAdapter;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.database.connection.ConnectionPoolFactory;
import com.consilens.core.database.dialect.DialectFactory;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Helper class for integration test database setup.
 * Provides methods for creating DatabaseAdapter instances, initializing test tables, and inserting test data.
 */
public final class TestDatabaseHelper {

    private TestDatabaseHelper() {
    }

    /**
     * Creates a DatabaseAdapter instance.
     */
    public static DatabaseAdapter createAdapter(String name, String jdbcUrl, String username,
                                                String password, DatabaseType dbType,
                                                ChecksumAlgorithm algorithm) {
        PoolConfiguration poolConfig = new PoolConfiguration();
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        poolConfig.setDatabaseType(dbType);
        poolConfig.setMaxPoolSize(5);
        poolConfig.setMinIdle(1);
        poolConfig.setConnectionTimeout(10000);
        poolConfig.setValidationQuery("SELECT 1");

        ConnectionPool pool = ConnectionPoolFactory.createPool(
                jdbcUrl, username, password, dbType, poolConfig);
        DatabaseDialect dialect = DialectFactory.getDialect(dbType);
        return new DefaultDatabaseAdapter(name, pool, dialect, jdbcUrl, algorithm);
    }

    /**
     * Creates a test table in the database.
     */
    public static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute("CREATE TABLE " + tableName + " ("
                    + "id INT PRIMARY KEY, "
                    + "name VARCHAR(100), "
                    + "value DECIMAL(10,2), "
                    + "status VARCHAR(20)"
                    + ")");
        }
    }

    /**
     * Inserts standard test data (id 1-10) into the test table.
     */
    public static void insertStandardData(DatabaseAdapter adapter, String tableName) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = 1; i <= 10; i++) {
                stmt.execute(String.format(
                        "INSERT INTO %s (id, name, value, status) VALUES (%d, 'item_%d', %s, 'active')",
                        tableName, i, i, String.valueOf(i * 10.50)));
            }
        }
    }

    /**
     * Inserts data for a specified id range into the test table.
     */
    public static void insertDataRange(DatabaseAdapter adapter, String tableName,
                                       int startId, int endId) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = startId; i <= endId; i++) {
                stmt.execute(String.format(
                        "INSERT INTO %s (id, name, value, status) VALUES (%d, 'item_%d', %s, 'active')",
                        tableName, i, i, String.valueOf(i * 10.50)));
            }
        }
    }

    /**
     * Updates a row's data (used to introduce differences).
     */
    public static void updateRow(DatabaseAdapter adapter, String tableName,
                                 int id, String newName, double newValue) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                    "UPDATE %s SET name = '%s', value = %s WHERE id = %d",
                    tableName, newName, String.valueOf(newValue), id));
        }
    }

    /**
     * Deletes the specified row.
     */
    public static void deleteRow(DatabaseAdapter adapter, String tableName, int id) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format("DELETE FROM %s WHERE id = %d", tableName, id));
        }
    }

    /**
     * Inserts an extra row (used to produce SOURCE_MISSING / TARGET_MISSING differences).
     */
    public static void insertExtraRow(DatabaseAdapter adapter, String tableName,
                                      int id, String name, double value) throws Exception {
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                    "INSERT INTO %s (id, name, value, status) VALUES (%d, '%s', %s, 'active')",
                    tableName, id, name, String.valueOf(value)));
        }
    }

    /**
     * Creates a large table and inserts bulk data.
     */
    public static void createAndPopulateLargeTable(DatabaseAdapter adapter, String tableName,
                                                   int rowCount) throws Exception {
        createTestTable(adapter, tableName);
        try (Connection conn = adapter.getConnection();
             Statement stmt = conn.createStatement()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= rowCount; i++) {
                sb.append(String.format(
                        "INSERT INTO %s (id, name, value, status) VALUES (%d, 'item_%d', %s, '%s');",
                        tableName, i, i, String.valueOf(i * 1.5),
                        i % 3 == 0 ? "inactive" : "active"));
                if (i % 100 == 0) {
                    stmt.execute(sb.toString().replace(";", ";\n"));
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) {
                stmt.execute(sb.toString().replace(";", ";\n"));
            }
        }
    }
}
