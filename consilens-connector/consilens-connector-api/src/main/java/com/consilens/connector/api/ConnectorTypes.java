package com.consilens.connector.api;

/**
 * Canonical connector type identifiers shared by connector providers, dialect
 * providers and dialect implementations.
 *
 * <p>The values are part of the public configuration/SPI surface and must not
 * change.
 */
public final class ConnectorTypes {

    public static final String MYSQL = "mysql";
    public static final String POSTGRESQL = "postgresql";
    public static final String ORACLE = "oracle";
    public static final String SQLSERVER = "sqlserver";
    public static final String CLICKHOUSE = "clickhouse";
    public static final String DORIS = "doris";
    public static final String STARROCKS = "starrocks";
    public static final String TIDB = "tidb";
    public static final String OCEANBASE = "oceanbase";
    public static final String PRESTO = "presto";
    public static final String TRINO = "trino";

    private ConnectorTypes() {
    }
}
