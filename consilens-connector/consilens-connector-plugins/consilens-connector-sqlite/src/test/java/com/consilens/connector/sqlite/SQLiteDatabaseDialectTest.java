package com.consilens.connector.sqlite;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.DataType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            if (provider.getDatabaseType() == com.consilens.connector.api.enums.DatabaseType.SQLITE) {
                DatabaseDialect dialect = provider.create();
                assertEquals(com.consilens.connector.api.enums.DatabaseType.SQLITE, dialect.getDatabaseType());
                found = true;
                break;
            }
        }

        assertTrue(found, "Expected SQLite provider to be discoverable via ServiceLoader");
    }

    @Test
    void shouldNormalizeSharedScalarTypesExplicitlyForCompare() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        assertEquals("COALESCE(TRIM(CAST(\"name\" AS TEXT)), '')",
                dialect.getDataTypeHandler().normalizeColumn("name", DataType.VARCHAR));
        assertEquals("COALESCE(TRIM(CAST(CAST(\"id\" AS INTEGER) AS TEXT)), '0')",
                dialect.getDataTypeHandler().normalizeColumn("id", DataType.INTEGER));
        assertEquals("CASE WHEN LOWER(TRIM(CAST(\"is_active\" AS TEXT))) IN ('1', 'true', 't', 'y', 'yes') THEN '1' ELSE '0' END",
                dialect.getDataTypeHandler().normalizeColumn("is_active", DataType.BOOLEAN));
        assertEquals("COALESCE(strftime('%Y-%m-%d %H:%M:%S', \"created_at\"), '')",
                dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP));
        assertEquals("COALESCE(HEX(\"payload\"), '')",
                dialect.getDataTypeHandler().normalizeColumn("payload", DataType.BLOB));
    }

    @Test
    void shouldMapSqliteDeclaredTypesNeededForCompare() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        assertEquals(DataType.INTEGER, dialect.getDataTypeHandler().convertToDataType("INTEGER"));
        assertEquals(DataType.DECIMAL, dialect.getDataTypeHandler().convertToDataType("NUMERIC(10,2)"));
        assertEquals(DataType.VARCHAR, dialect.getDataTypeHandler().convertToDataType("VARCHAR(64)"));
        assertEquals(DataType.TIMESTAMP, dialect.getDataTypeHandler().convertToDataType("TIMESTAMP"));
        assertEquals(DataType.BLOB, dialect.getDataTypeHandler().convertToDataType("BLOB"));
    }

    @Test
    void shouldRejectChecksumSqlGenerationBecauseSqliteHashesAreComputedInCode() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("amount", DataType.DECIMAL);

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> dialect.getSqlQueryGenerator().getChecksumSQL(
                        null,
                        "users",
                        Arrays.asList("id"),
                        Arrays.asList("id", "name", "amount"),
                        types,
                        "id >= 100",
                        ChecksumAlgorithm.CONCAT));

        assertTrue(exception.getMessage().contains("computed in code"));
    }

    @Test
    void shouldPreserveExactIntegerMagnitudeWhenNormalizingDecimalValues() throws Exception {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();
        String expression = dialect.getDataTypeHandler().normalizeColumn("amount", DataType.DECIMAL);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (amount NUMERIC)");
            statement.execute("INSERT INTO sample(amount) VALUES (9007199254740993)");

            try (ResultSet resultSet = statement.executeQuery("SELECT " + expression + " AS normalized_amount FROM sample")) {
                assertTrue(resultSet.next());
                assertEquals("9007199254740993.0000", resultSet.getString("normalized_amount"));
            }
        }
    }

    @Test
    void shouldRejectXorChecksumSqlForSqlite() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();
        Map<String, DataType> types = new HashMap<>();
        types.put("id", DataType.INTEGER);

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> dialect.getSqlQueryGenerator().getChecksumSQL(
                        null,
                        "users",
                        Arrays.asList("id"),
                        Arrays.asList("id"),
                        types,
                        null,
                        ChecksumAlgorithm.XOR));

        assertTrue(exception.getMessage().contains("computed in code"));
    }

    @Test
    void shouldRejectRowHashSqlGenerationBecauseSqliteHashesAreComputedInCode() {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();
        Map<String, DataType> types = new HashMap<>();
        types.put("tenant_id", DataType.INTEGER);
        types.put("user_id", DataType.INTEGER);
        types.put("name", DataType.VARCHAR);
        types.put("created_at", DataType.TIMESTAMP);

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> dialect.getSqlQueryGenerator().getRowHashSQL(
                        null,
                        "users",
                        Arrays.asList("tenant_id", "user_id"),
                        Arrays.asList("tenant_id", "user_id", "name", "created_at"),
                        types,
                        "tenant_id = 9"));

        assertTrue(exception.getMessage().contains("computed in code"));
    }
}
