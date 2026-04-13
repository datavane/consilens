package com.consilens.connector.api.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseTypeTest {

    @Test
    void shouldDetectSqliteOnlyFromJdbcSqlitePrefix() {
        assertEquals(DatabaseType.SQLITE, DatabaseType.fromJdbcUrl("jdbc:sqlite:/tmp/source.db"));
        assertEquals(DatabaseType.UNKNOWN, DatabaseType.fromJdbcUrl("jdbc:custom://localhost:9999/sqlite_demo"));
    }

    @Test
    void shouldKeepRecognizingKnownJdbcSchemesEvenWhenPathContainsSqlite() {
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromJdbcUrl("jdbc:mysql://localhost:3306/sqlite_demo"));
        assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromJdbcUrl("jdbc:postgresql://localhost:5432/sqlite_demo"));
    }
}
