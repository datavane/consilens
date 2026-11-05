package com.consilens.sink.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TableSinkConfigDriverTest {

    @Test
    void resolvesKnownDialectsToTheirDrivers() {
        assertEquals("com.mysql.cj.jdbc.Driver", config("mysql").resolveDriver());
        assertEquals("com.mysql.cj.jdbc.Driver", config("doris").resolveDriver());
        assertEquals("org.postgresql.Driver", config("postgresql").resolveDriver());
        assertEquals("oracle.jdbc.OracleDriver", config("oracle").resolveDriver());
        assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver", config("sqlserver").resolveDriver());
        assertEquals("com.clickhouse.jdbc.ClickHouseDriver", config("clickhouse").resolveDriver());
    }

    @Test
    void unknownDialectFallsBackToDriverManagerInsteadOfFailing() {
        // CLI 侧按 SPI 注册表校验会放过这些方言；运行时不能再抛 "only supports mysql and postgresql"
        assertNull(config("h2").resolveDriver());
        assertNull(config("duckdb").resolveDriver());
    }

    @Test
    void explicitDriverWins() {
        TableSinkConfig config = config("h2");
        config.setDriver(" org.h2.Driver ");
        assertEquals("org.h2.Driver", config.resolveDriver());
    }

    private TableSinkConfig config(String type) {
        TableSinkConfig config = new TableSinkConfig();
        config.setType(type);
        return config;
    }
}
