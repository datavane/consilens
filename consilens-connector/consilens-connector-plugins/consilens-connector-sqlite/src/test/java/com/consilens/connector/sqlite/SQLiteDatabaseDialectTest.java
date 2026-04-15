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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        String normalizedDateTime = dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.DATETIME);
        String normalizedTimestamp = dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP);
        String normalizedTimestampWithTimezone = dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP_WITH_TIMEZONE);

        assertTrue(normalizedDateTime.contains("CASE WHEN \"created_at\" IS NULL THEN ''"));
        assertTrue(normalizedDateTime.contains("GLOB '????-??-?? ??:??:??'"));
        assertTrue(normalizedTimestamp.contains("strftime('%Y-%m-%d %H:%M:%S'"));
        assertTrue(normalizedTimestamp.contains("'utc'"));
        assertTrue(normalizedTimestamp.contains("LIKE '%Z'"));
        assertTrue(normalizedTimestamp.contains("typeof(\"created_at\") IN ('integer', 'real')"));
        assertTrue(normalizedTimestamp.contains("BETWEEN 0 AND 23"));
        assertTrue(normalizedTimestamp.contains("BETWEEN 0 AND 59"));
        assertEquals(normalizedTimestamp, normalizedTimestampWithTimezone);
        assertNotEquals(
                dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.DATETIME),
                dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP));
        assertEquals("COALESCE(HEX(\"payload\"), '')",
                dialect.getDataTypeHandler().normalizeColumn("payload", DataType.BLOB));
    }

    @Test
    void shouldNormalizeSqliteTemporalTypesWithExpectedTimezoneSemantics() throws Exception {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (datetime_col TEXT, timestamp_col TEXT, timestamptz_col TEXT, naive_timestamp_col TEXT, minute_datetime_col TEXT, invalid_datetime_col TEXT, compact_timestamp_col TEXT, garbage_suffix_datetime_col TEXT, minute_z_timestamp_col TEXT)");
            statement.execute("INSERT INTO sample(datetime_col, timestamp_col, timestamptz_col, naive_timestamp_col, minute_datetime_col, invalid_datetime_col, compact_timestamp_col, garbage_suffix_datetime_col, minute_z_timestamp_col) VALUES ("
                    + "'2026-04-14 08:09:10+08:00', "
                    + "'2026-04-14 08:09:10+08:00', "
                    + "'2026-04-14 08:09:10+08:00', "
                    + "'2026-04-14 08:09:10', "
                    + "'2026-04-14 08:09', "
                    + "'not-a-date', "
                    + "'2026-04-14 08:09:10+0800', "
                    + "'2026-04-14 08:09:10foo', "
                    + "'2026-04-14 08:09Z')");

            String query = "SELECT "
                    + dialect.getDataTypeHandler().normalizeColumn("datetime_col", DataType.DATETIME) + " AS normalized_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("timestamp_col", DataType.TIMESTAMP) + " AS normalized_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("timestamptz_col", DataType.TIMESTAMP_WITH_TIMEZONE) + " AS normalized_timestamptz, "
                    + dialect.getDataTypeHandler().normalizeColumn("naive_timestamp_col", DataType.TIMESTAMP) + " AS normalized_naive_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("minute_datetime_col", DataType.DATETIME) + " AS normalized_minute_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("invalid_datetime_col", DataType.DATETIME) + " AS normalized_invalid_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("compact_timestamp_col", DataType.TIMESTAMP) + " AS normalized_compact_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("garbage_suffix_datetime_col", DataType.DATETIME) + " AS normalized_garbage_suffix_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("minute_z_timestamp_col", DataType.TIMESTAMP) + " AS normalized_minute_z_timestamp "
                    + "FROM sample";

            try (ResultSet resultSet = statement.executeQuery(query)) {
                assertTrue(resultSet.next());
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_datetime"));
                assertEquals("2026-04-14 00:09:10", resultSet.getString("normalized_timestamp"));
                assertEquals("2026-04-14 00:09:10", resultSet.getString("normalized_timestamptz"));
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_naive_timestamp"));
                assertEquals("2026-04-14 08:09:00", resultSet.getString("normalized_minute_datetime"));
                assertEquals("", resultSet.getString("normalized_invalid_datetime"));
                assertEquals("2026-04-14 00:09:10", resultSet.getString("normalized_compact_timestamp"));
                assertEquals("", resultSet.getString("normalized_garbage_suffix_datetime"));
                assertEquals("2026-04-14 08:09:00", resultSet.getString("normalized_minute_z_timestamp"));
            }
        }
    }

    @Test
    void shouldRejectMalformedTemporalSuffixesAndSupportJulianDayValues() throws Exception {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (malformed_fraction_col TEXT, real_julian_datetime_col REAL, real_julian_timestamp_col REAL, invalid_timezone_datetime_col TEXT, valid_fraction_datetime_col TEXT, epoch_datetime_col INTEGER, epoch_timestamp_col INTEGER, epoch_zero_datetime_col INTEGER, epoch_day_timestamp_col INTEGER, negative_epoch_datetime_col INTEGER)");
            statement.execute("INSERT INTO sample(malformed_fraction_col, real_julian_datetime_col, real_julian_timestamp_col, invalid_timezone_datetime_col, valid_fraction_datetime_col, epoch_datetime_col, epoch_timestamp_col, epoch_zero_datetime_col, epoch_day_timestamp_col, negative_epoch_datetime_col) VALUES ("
                    + "'2026-04-14 08:09:10.abc', "
                    + "julianday('2026-04-14 08:09:10'), "
                    + "julianday('2026-04-14 08:09:10'), "
                    + "'2026-04-14 08:09:10+99:99', "
                    + "'2026-04-14 08:09:10.123', "
                    + "1713082150, "
                    + "1713082150, "
                    + "0, "
                    + "86400, "
                    + "-1)");

            String textAndJulianQuery = "SELECT "
                    + dialect.getDataTypeHandler().normalizeColumn("malformed_fraction_col", DataType.TIMESTAMP) + " AS normalized_malformed_fraction, "
                    + dialect.getDataTypeHandler().normalizeColumn("real_julian_datetime_col", DataType.DATETIME) + " AS normalized_real_julian_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("real_julian_timestamp_col", DataType.TIMESTAMP) + " AS normalized_real_julian_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("invalid_timezone_datetime_col", DataType.DATETIME) + " AS normalized_invalid_timezone_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("valid_fraction_datetime_col", DataType.DATETIME) + " AS normalized_valid_fraction_datetime "
                    + "FROM sample";

            try (ResultSet resultSet = statement.executeQuery(textAndJulianQuery)) {
                assertTrue(resultSet.next());
                assertEquals("", resultSet.getString("normalized_malformed_fraction"));
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_real_julian_datetime"));
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_real_julian_timestamp"));
                assertEquals("", resultSet.getString("normalized_invalid_timezone_datetime"));
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_valid_fraction_datetime"));
            }

            String epochQuery = "SELECT "
                    + dialect.getDataTypeHandler().normalizeColumn("epoch_datetime_col", DataType.DATETIME) + " AS normalized_epoch_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("epoch_timestamp_col", DataType.TIMESTAMP) + " AS normalized_epoch_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("epoch_zero_datetime_col", DataType.DATETIME) + " AS normalized_epoch_zero_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("epoch_day_timestamp_col", DataType.TIMESTAMP) + " AS normalized_epoch_day_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("negative_epoch_datetime_col", DataType.DATETIME) + " AS normalized_negative_epoch_datetime "
                    + "FROM sample";

            try (ResultSet resultSet = statement.executeQuery(epochQuery)) {
                assertTrue(resultSet.next());
                assertEquals("2024-04-14 08:09:10", resultSet.getString("normalized_epoch_datetime"));
                assertEquals("2024-04-14 08:09:10", resultSet.getString("normalized_epoch_timestamp"));
                assertEquals("1970-01-01 00:00:00", resultSet.getString("normalized_epoch_zero_datetime"));
                assertEquals("1970-01-02 00:00:00", resultSet.getString("normalized_epoch_day_timestamp"));
                assertEquals("1969-12-31 23:59:59", resultSet.getString("normalized_negative_epoch_datetime"));
            }
        }
    }

    @Test
    void shouldNormalizeFractionalTimezoneTimestampsWithoutExpandingSqlTooMuch() throws Exception {
        SQLiteDatabaseDialect dialect = new SQLiteDatabaseDialect();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (fractional_datetime_col TEXT, fractional_z_timestamp_col TEXT, fractional_colon_timestamp_col TEXT, fractional_compact_timestamp_col TEXT)");
            statement.execute("INSERT INTO sample(fractional_datetime_col, fractional_z_timestamp_col, fractional_colon_timestamp_col, fractional_compact_timestamp_col) VALUES ("
                    + "'2026-04-14 08:09:10.123+08:00', "
                    + "'2026-04-14 08:09:10.123Z', "
                    + "'2026-04-14 08:09:10.123+08:00', "
                    + "'2026-04-14 08:09:10.123+0800')");

            String query = "SELECT "
                    + dialect.getDataTypeHandler().normalizeColumn("fractional_datetime_col", DataType.DATETIME) + " AS normalized_fractional_datetime, "
                    + dialect.getDataTypeHandler().normalizeColumn("fractional_z_timestamp_col", DataType.TIMESTAMP) + " AS normalized_fractional_z_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("fractional_colon_timestamp_col", DataType.TIMESTAMP) + " AS normalized_fractional_colon_timestamp, "
                    + dialect.getDataTypeHandler().normalizeColumn("fractional_compact_timestamp_col", DataType.TIMESTAMP) + " AS normalized_fractional_compact_timestamp "
                    + "FROM sample";

            try (ResultSet resultSet = statement.executeQuery(query)) {
                assertTrue(resultSet.next());
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_fractional_datetime"));
                assertEquals("2026-04-14 08:09:10", resultSet.getString("normalized_fractional_z_timestamp"));
                assertEquals("2026-04-14 00:09:10", resultSet.getString("normalized_fractional_colon_timestamp"));
                assertEquals("2026-04-14 00:09:10", resultSet.getString("normalized_fractional_compact_timestamp"));
            }
        }
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
