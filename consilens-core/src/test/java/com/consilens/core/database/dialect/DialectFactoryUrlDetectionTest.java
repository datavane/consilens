package com.consilens.core.database.dialect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the URL-to-connector-type mapping while matching moves to provider
 * self-reported rules.
 */
class DialectFactoryUrlDetectionTest {

    @Test
    void shouldDetectProviderBackedConnectorTypes() {
        assertEquals("mysql", DialectFactory.connectorTypeFromJdbcUrl("jdbc:mysql://host:3306/db"));
        assertEquals("tidb", DialectFactory.connectorTypeFromJdbcUrl("jdbc:mysql://host:4000/db"));
        assertEquals("tidb", DialectFactory.connectorTypeFromJdbcUrl("jdbc:tidb://host/db"));
        assertEquals("starrocks", DialectFactory.connectorTypeFromJdbcUrl("jdbc:mysql://host:9030/db"));
        assertEquals("starrocks", DialectFactory.connectorTypeFromJdbcUrl("jdbc:starrocks://host/db"));
        assertEquals("doris", DialectFactory.connectorTypeFromJdbcUrl("jdbc:mysql://host/db?tags=doris"));
        assertEquals("postgresql", DialectFactory.connectorTypeFromJdbcUrl("jdbc:postgresql://host:5432/db"));
        assertEquals("postgresql", DialectFactory.connectorTypeFromJdbcUrl("jdbc:postgres://host/db"));
        assertEquals("oracle", DialectFactory.connectorTypeFromJdbcUrl("jdbc:oracle:thin:@host:1521:xe"));
        assertEquals("sqlserver", DialectFactory.connectorTypeFromJdbcUrl("jdbc:sqlserver://host;databaseName=db"));
        assertEquals("clickhouse", DialectFactory.connectorTypeFromJdbcUrl("jdbc:clickhouse://host:8123/db"));
        assertEquals("presto", DialectFactory.connectorTypeFromJdbcUrl("jdbc:presto://host:8080/db"));
        assertEquals("trino", DialectFactory.connectorTypeFromJdbcUrl("jdbc:trino://host:8080/db"));
    }

    @Test
    void shouldKeepLegacyResultsForConnectorTypesWithoutProviders() {
        assertEquals("sqlite", DialectFactory.connectorTypeFromJdbcUrl("jdbc:sqlite:/tmp/db"));
        assertEquals("h2", DialectFactory.connectorTypeFromJdbcUrl("jdbc:h2:mem:db"));
        assertEquals("snowflake", DialectFactory.connectorTypeFromJdbcUrl("jdbc:snowflake://host"));
        assertEquals("bigquery", DialectFactory.connectorTypeFromJdbcUrl("jdbc:bigquery://host"));
        assertEquals("redshift", DialectFactory.connectorTypeFromJdbcUrl("jdbc:redshift://host"));
        assertEquals("databricks", DialectFactory.connectorTypeFromJdbcUrl("jdbc:databricks://host"));
        assertEquals("vertica", DialectFactory.connectorTypeFromJdbcUrl("jdbc:vertica://host"));
        assertEquals("duckdb", DialectFactory.connectorTypeFromJdbcUrl("jdbc:duckdb:db"));
    }

    @Test
    void shouldReturnUnknownForMissingOrUnrecognizedUrls() {
        assertEquals("unknown", DialectFactory.connectorTypeFromJdbcUrl(null));
        assertEquals("unknown", DialectFactory.connectorTypeFromJdbcUrl("jdbc:oceanbase://host/db"));
        assertEquals("unknown", DialectFactory.connectorTypeFromJdbcUrl("jdbc:unknown://host/db"));
    }
}
