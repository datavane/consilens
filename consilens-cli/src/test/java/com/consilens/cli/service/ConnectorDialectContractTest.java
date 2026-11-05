package com.consilens.cli.service;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.MetadataQueryGenerator;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.spi.ConnectorProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全 Connector 的快速契约门禁。它不连接数据库，而是保证插件发现、方言装配和核心 SQL
 * 生成入口在每次 Maven package 时全部可用；精确方言字符串继续由各插件测试负责。
 */
class ConnectorDialectContractTest {

    @Test
    void shouldKeepExamplesConnectorProvidersAndDialectsInSync() throws IOException {
        Set<String> expectedTypes = exampleConnectorTypes();
        Map<String, ConnectorProvider> connectorProviders = loadProviders(
                ConnectorProvider.class, ConnectorProvider::getType);
        Map<String, DatabaseDialectProvider> dialectProviders = loadProviders(
                DatabaseDialectProvider.class, DatabaseDialectProvider::getConnectorType);

        // 只校验 JDBC 方言连接器与 same-db 示例一一对应；file 等非 JDBC 源没有方言
        Set<String> jdbcProviderTypes = connectorProviders.keySet().stream()
                .filter(dialectProviders::containsKey)
                .collect(Collectors.toSet());
        assertEquals(expectedTypes, jdbcProviderTypes,
                "same-db 示例与 JDBC ConnectorProvider 清单不一致");
        assertEquals(expectedTypes, dialectProviders.keySet(),
                "same-db 示例与 DatabaseDialectProvider 清单不一致");

        for (String type : expectedTypes) {
            verifyDialectContract(type, dialectProviders.get(type));
        }
    }

    private void verifyDialectContract(String type, DatabaseDialectProvider provider) {
        DatabaseDialect dialect = provider.create();
        assertEquals(type, dialect.getConnectorType(), type + " 方言类型不一致");
        assertNotNull(dialect.getCapabilityProvider(), type + " 缺少 capability provider");
        assertNotNull(dialect.getTransactionManager(), type + " 缺少 transaction manager");
        assertNotNull(dialect.getConnectionPoolOptimizer(), type + " 缺少 pool optimizer");
        assertNotNull(dialect.getDataTypeHandler(), type + " 缺少 data type handler");

        SqlQueryGenerator sql = dialect.getSqlQueryGenerator();
        assertNotNull(sql, type + " 缺少 SQL generator");
        verifySqlGeneratorContract(type, sql);

        MetadataQueryGenerator metadata = dialect.getMetadataQueryGenerator();
        assertNotNull(metadata, type + " 缺少 metadata generator");
        verifyMetadataGeneratorContract(type, metadata);

        assertSql(type, "timestamp normalization",
                dialect.getDataTypeHandler().normalizeColumn("created_at", DataType.TIMESTAMP));
        assertNotNull(dialect.getDataTypeHandler().convertToTypeDescriptor("VARCHAR(255)"),
                type + " 无法转换 VARCHAR 类型");
    }

    private void verifySqlGeneratorContract(String type, SqlQueryGenerator sql) {
        List<String> keys = List.of("id");
        List<String> columns = List.of("id", "amount");
        List<List<Object>> primaryKeys = List.of(List.of(1));
        Map<String, DataType> dataTypes = Map.of("id", DataType.INTEGER, "amount", DataType.DECIMAL);
        String sourceSql = "SELECT id, amount FROM orders";

        assertSql(type, "limit", sql.getLimitClause(10, 20));
        assertSql(type, "limit without offset", sql.getLimitClause(20));
        assertSql(type, "count", sql.getCountSQL("regression", "orders", "id > 0"));
        assertSql(type, "count from SQL resource", sql.getCountSQLFromSql(sourceSql, "id > 0"));
        assertSql(type, "select", sql.getSelectSQL("regression", "orders", columns, "id > 0", keys));
        assertSql(type, "select from SQL resource", sql.getSelectSQLFromSql(sourceSql, columns, "id > 0", keys));
        assertSql(type, "select by keys", sql.getSelectByKeysSQL(
                "regression", "orders", columns, keys, primaryKeys, "id > 0", keys));
        assertSql(type, "select by keys from SQL resource", sql.getSelectByKeysSQLFromSql(
                sourceSql, columns, keys, primaryKeys, "id > 0", keys));
        assertSql(type, "min key", sql.getMinMaxKeySQL("regression", "orders", keys, true, "id > 0"));
        assertSql(type, "max key from SQL resource", sql.getMinMaxKeySQLFromSql(sourceSql, keys, false, "id > 0"));
        assertSql(type, "checksum concat", sql.getChecksumSQL(
                "regression", "orders", keys, columns, dataTypes, "id > 0", ChecksumAlgorithm.CONCAT));
        assertSql(type, "checksum concat from SQL resource", sql.getChecksumSQLFromSql(
                sourceSql, keys, columns, dataTypes, "id > 0", ChecksumAlgorithm.CONCAT));
        assertSql(type, "backward-compatible checksum", sql.getChecksumSQL(
                "regression", "orders", keys, columns, dataTypes, "id > 0"));
        if (sql.supportsChecksumAlgorithm(ChecksumAlgorithm.XOR)) {
            assertSql(type, "checksum xor", sql.getChecksumSQL(
                    "regression", "orders", keys, columns, dataTypes, "id > 0", ChecksumAlgorithm.XOR));
        }
        assertSql(type, "distinct count", sql.getDistinctCountSQL("regression", "orders", columns, "id > 0"));
        assertSql(type, "distinct count from SQL resource", sql.getDistinctCountSQLFromSql(
                sourceSql, columns, "id > 0"));
        assertSql(type, "insert", sql.getInsertSQL("orders", columns));
        assertSql(type, "batch insert", sql.getBatchInsertSQL("orders", columns, 2));
        assertSql(type, "drop table", sql.getDropTableSQL("orders", true));
        assertSql(type, "create temp table", sql.getCreateTempTableSQL("orders_tmp", sourceSql));
        assertSql(type, "row hash", sql.getRowHashSQL(
                "regression", "orders", keys, columns, dataTypes, "id > 0"));
        assertSql(type, "row hash from SQL resource", sql.getRowHashSQLFromSql(
                sourceSql, keys, columns, dataTypes, "id > 0"));
        assertSql(type, "join diff stats", sql.getJoinDiffStatsSQL(
                "regression", "source_orders", "s", keys, columns, "s.id > 0",
                "regression", "target_orders", "t", keys, columns, "t.id > 0"));
        assertSql(type, "join diff detail", sql.getJoinDiffDetailSQL(
                "regression", "source_orders", "s", keys, columns, columns, "s.id > 0",
                "regression", "target_orders", "t", keys, columns, columns, "t.id > 0"));
        assertEquals("NULL", sql.formatValue(null), type + " 未正确格式化 null SQL 字面量");
        assertEquals("'O''Hara'", sql.formatValue("O'Hara"), type + " 未正确转义字符串 SQL 字面量");
    }

    private void verifyMetadataGeneratorContract(String type, MetadataQueryGenerator metadata) {
        assertSql(type, "table exists", metadata.getTableExistsSQL("regression", "orders"));
        assertSql(type, "column exists", metadata.getColumnExistsSQL("regression", "orders", "amount"));
        assertSql(type, "primary key", metadata.getPrimaryKeySQL("regression", "orders"));
        assertSql(type, "table columns", metadata.getTableColumnsSQL("regression", "orders"));
        assertSql(type, "primary keys", metadata.getPrimaryKeysSQL("regression", "orders"));
        assertSql(type, "foreign keys", metadata.getForeignKeysSQL("regression", "orders"));
        assertSql(type, "indexes", metadata.getIndexesSQL("regression", "orders"));
        assertSql(type, "database metadata", metadata.getDatabaseMetadataSQL());
        assertSql(type, "schemas", metadata.getSchemasSQL());
        assertSql(type, "tables", metadata.getTablesSQL("regression"));
        assertSql(type, "views", metadata.getViewsSQL("regression"));
        assertSql(type, "health check", metadata.getHealthCheckSQL());
        assertSql(type, "analyze table", metadata.getAnalyzeTableSQL("regression", "orders"));
        assertSql(type, "optimize table", metadata.getOptimizeTableSQL("regression", "orders"));
    }

    private void assertSql(String type, String capability, String sql) {
        assertNotNull(sql, type + " 的 " + capability + " SQL 为 null");
        assertFalse(sql.trim().isEmpty(), type + " 的 " + capability + " SQL 为空");
    }

    private Set<String> exampleConnectorTypes() throws IOException {
        Path sameDb = examplesDirectory().resolve("same-db");
        try (var paths = Files.list(sameDb)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private Path examplesDirectory() {
        try {
            Path testClasses = Paths.get(ConnectorDialectContractTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Path examples = testClasses.getParent().getParent().getParent().resolve("examples").normalize();
            if (!Files.isDirectory(examples)) {
                throw new IllegalStateException("examples directory not found at: " + examples);
            }
            return examples;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate examples directory", e);
        }
    }

    private <T> Map<String, T> loadProviders(Class<T> providerType, Function<T, String> typeExtractor) {
        return ServiceLoader.load(providerType).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toMap(typeExtractor, Function.identity()));
    }
}
