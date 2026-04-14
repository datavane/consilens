package com.consilens.core.database.adpter;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.database.connection.ConnectionPoolFactory;
import com.consilens.core.database.dialect.DialectFactory;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractDatabaseAdapterSQLiteHashingTest {

    @AfterEach
    void tearDown() {
        ConnectionPoolFactory.closeAllPools();
    }

    @Test
    void shouldComputeSqliteChecksumInCodeFromNormalizedRows() throws Exception {
        DefaultDatabaseAdapter adapter = createSqliteAdapter();
        try {
            createSampleTable(adapter);

            TableSegment segment = TableSegment.builder()
                    .database(adapter)
                    .tablePath(com.consilens.connector.api.model.TablePath.of("sample"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "amount", "created_at"))
                    .minKey(Optional.of(Arrays.asList("1")))
                    .maxKey(Optional.of(Arrays.asList("3")))
                    .build();

            TableSegment.ChecksumResult result = adapter.countAndChecksum(segment);

            assertEquals(2, result.getCount());
            assertEquals(md5(md5("1|Alice|10.5000|2026-04-14 08:09:10") + "|" + md5("2|Bob|11.0000|2026-04-14 08:09:11")), result.getChecksum());
            assertEquals(Arrays.asList("1"), result.getMinKey());
            assertEquals(Arrays.asList("2"), result.getMaxKey());
        } finally {
            adapter.close();
        }
    }

    @Test
    void shouldComputeSqliteRowHashesInCodeFromNormalizedRows() throws Exception {
        DefaultDatabaseAdapter adapter = createSqliteAdapter();
        try {
            createSampleTable(adapter);

            TableSegment segment = TableSegment.builder()
                    .database(adapter)
                    .tablePath(com.consilens.connector.api.model.TablePath.of("sample"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "amount", "created_at"))
                    .minKey(Optional.of(Arrays.asList("1")))
                    .maxKey(Optional.of(Arrays.asList("4")))
                    .build();

            Map<List<Object>, String> hashes = adapter.querySegmentRowHashes(segment);

            assertEquals(3, hashes.size());
            assertEquals(md5("1|Alice|10.5000|2026-04-14 08:09:10"), hashes.get(Arrays.asList(1)));
            assertEquals(md5("2|Bob|11.0000|2026-04-14 08:09:11"), hashes.get(Arrays.asList(2)));
            assertEquals(md5("3||0.0000|"), hashes.get(Arrays.asList(3)));
        } finally {
            adapter.close();
        }
    }

    @Test
    void shouldRejectXorChecksumForSqliteCodeSideHashing() throws Exception {
        DefaultDatabaseAdapter adapter = createSqliteAdapter(ChecksumAlgorithm.XOR);
        try {
            createSampleTable(adapter);

            TableSegment segment = TableSegment.builder()
                    .database(adapter)
                    .tablePath(com.consilens.connector.api.model.TablePath.of("sample"))
                    .keyColumns(Arrays.asList("id"))
                    .extraColumns(Arrays.asList("name", "amount", "created_at"))
                    .build();

            UnsupportedOperationException exception = assertThrows(
                    UnsupportedOperationException.class,
                    () -> adapter.countAndChecksum(segment));
            assertEquals("SQLite code-side checksum only supports CONCAT algorithm", exception.getMessage());
        } finally {
            adapter.close();
        }
    }

    private DefaultDatabaseAdapter createSqliteAdapter() {
        return createSqliteAdapter(ChecksumAlgorithm.CONCAT);
    }

    private DefaultDatabaseAdapter createSqliteAdapter(ChecksumAlgorithm checksumAlgorithm) {
        PoolConfiguration poolConfig = new PoolConfiguration();
        poolConfig.setJdbcUrl("jdbc:sqlite::memory:");
        poolConfig.setDatabaseType(DatabaseType.SQLITE);
        poolConfig.setMaxPoolSize(1);
        poolConfig.setMinIdle(1);
        poolConfig.setConnectionTimeout(10000);
        poolConfig.setValidationQuery("SELECT 1");

        ConnectionPool pool = ConnectionPoolFactory.createPool(
                "jdbc:sqlite::memory:", null, null, DatabaseType.SQLITE, poolConfig);
        DatabaseDialect dialect = DialectFactory.getDialect(DatabaseType.SQLITE);
        return new DefaultDatabaseAdapter("sqlite-test", pool, dialect, "jdbc:sqlite::memory:", checksumAlgorithm);
    }

    private void createSampleTable(DefaultDatabaseAdapter adapter) throws Exception {
        try (Connection connection = adapter.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, name TEXT, amount NUMERIC, created_at TEXT)");
            statement.execute("INSERT INTO sample(id, name, amount, created_at) VALUES (1, ' Alice ', 10.5, '2026-04-14 08:09:10')");
            statement.execute("INSERT INTO sample(id, name, amount, created_at) VALUES (2, 'Bob', 11, '2026-04-14 08:09:11')");
            statement.execute("INSERT INTO sample(id, name, amount, created_at) VALUES (3, NULL, NULL, NULL)");
        }
    }

    private String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
