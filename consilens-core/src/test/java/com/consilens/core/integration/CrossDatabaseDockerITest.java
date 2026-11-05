package com.consilens.core.integration;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.algorithm.ChecksumDiffer;
import com.consilens.core.algorithm.TableDiffer.DifferConfig;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database diff integration test using existing Docker databases.
 * Connects to databases started by the developer's local docker-compose setup.
 *
 * This test verifies that data loaded into different databases can be compared
 * using the ChecksumDiffer.
 */
@DisplayName("跨数据库 Docker 环境集成测试")
class CrossDatabaseDockerITest {

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter postgresAdapter;
    private static DatabaseAdapter oracleAdapter;
    private static DatabaseAdapter tidbAdapter;
    private static DatabaseAdapter clickhouseAdapter;
    private static DatabaseAdapter trinoAdapter;
    private static DatabaseAdapter prestoAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        String mysqlPassword = IntegrationCredentials.require("CONSILENS_IT_MYSQL_PASSWORD");
        String postgresPassword = IntegrationCredentials.require("CONSILENS_IT_POSTGRESQL_PASSWORD");
        String clickhousePassword = IntegrationCredentials.require("CONSILENS_IT_CLICKHOUSE_PASSWORD");

        // MySQL (port 13306)
        mysqlAdapter = TestDatabaseHelper.createAdapter(
                "mysql-source",
                "jdbc:mysql://127.0.0.1:13306/consilens_demo?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "root", mysqlPassword,
                "mysql", ChecksumAlgorithm.CONCAT);

        // PostgreSQL (port 5432)
        postgresAdapter = TestDatabaseHelper.createAdapter(
                "pg-source",
                "jdbc:postgresql://127.0.0.1:5432/consilens_demo",
                "consilens", postgresPassword,
                "postgresql", ChecksumAlgorithm.CONCAT);

        // Oracle (port 1521)
        oracleAdapter = TestDatabaseHelper.createAdapter(
                "oracle-source",
                "jdbc:oracle:thin:@127.0.0.1:1521:ORCL",
                "system", "oracle",
                "oracle", ChecksumAlgorithm.CONCAT);

        // TiDB (port 4000)
        tidbAdapter = TestDatabaseHelper.createAdapter(
                "tidb-source",
                "jdbc:mysql://127.0.0.1:4000/consilens_demo?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "root", "",
                "tidb", ChecksumAlgorithm.CONCAT);

        // ClickHouse (port 8123 for HTTP protocol)
        clickhouseAdapter = TestDatabaseHelper.createAdapter(
                "clickhouse-source",
                "jdbc:clickhouse://127.0.0.1:8123/consilens_demo",
                "default", clickhousePassword,
                "clickhouse", ChecksumAlgorithm.CONCAT);

        // Trino (port 8081) - skip validation query as Trino doesn't support SELECT 1
        trinoAdapter = createAdapterWithoutValidation(
                "trino-source",
                "jdbc:trino://127.0.0.1:8081/memory/consilens_demo",
                "test", "", "trino");

        // Presto (port 8085)
        prestoAdapter = createAdapterWithoutValidation(
                "presto-source",
                "jdbc:presto://127.0.0.1:8085/memory/consilens_demo",
                "test", "", "presto");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (postgresAdapter != null) postgresAdapter.close();
        if (oracleAdapter != null) oracleAdapter.close();
        if (tidbAdapter != null) tidbAdapter.close();
        if (clickhouseAdapter != null) clickhouseAdapter.close();
        if (trinoAdapter != null) trinoAdapter.close();
        if (prestoAdapter != null) prestoAdapter.close();
    }

    @Test
    @DisplayName("MySQL 和 TiDB 中相同数据应无差异")
    void mysqlAndTidbShouldHaveNoDifferences() throws Exception {
        DiffResult result = runDiff(
                mysqlAdapter, "consilens_demo", "cross_db_test",
                tidbAdapter, "consilens_demo", "cross_db_test",
                "id", "ID"
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    @Test
    @DisplayName("MySQL 和 PostgreSQL 中相同数据应无差异")
    void mysqlAndPostgresShouldHaveNoDifferences() throws Exception {
        // PostgreSQL uses lowercase column names
        DiffResult result = runDiff(
                mysqlAdapter, TablePath.of("consilens_demo", "cross_db_test"),
                postgresAdapter, TablePath.of("public", "cross_db_test"),
                "id", "id",
                Arrays.asList("name", "value", "status"),
                Arrays.asList("name", "value", "status")
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    @Test
    @DisplayName("MySQL 和 ClickHouse 中相同数据应无差异")
    void mysqlAndClickhouseShouldHaveNoDifferences() throws Exception {
        // ClickHouse stores column names in lowercase by default
        DiffResult result = runDiff(
                mysqlAdapter, TablePath.of("consilens_demo", "cross_db_test"),
                clickhouseAdapter, TablePath.of("consilens_demo", "cross_db_test"),
                "id", "ID",
                Arrays.asList("name", "value", "status"),
                Arrays.asList("name", "value", "status")
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    @Test
    @DisplayName("MySQL 和 Trino 中相同数据应无差异")
    void mysqlAndTrinoShouldHaveNoDifferences() throws Exception {
        DiffResult result = runDiff(
                mysqlAdapter, TablePath.of("consilens_demo", "cross_db_test"),
                trinoAdapter, TablePath.of("memory", "consilens_demo", "cross_db_test"),
                "id", "ID"
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    @Test
    @DisplayName("MySQL 和 Presto 中相同数据应无差异")
    void mysqlAndPrestoShouldHaveNoDifferences() throws Exception {
        DiffResult result = runDiff(
                mysqlAdapter, TablePath.of("consilens_demo", "cross_db_test"),
                prestoAdapter, TablePath.of("memory", "consilens_demo", "cross_db_test"),
                "id", "ID"
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    @Test
    @DisplayName("MySQL 和 Oracle 中相同数据应无差异")
    void mysqlAndOracleShouldHaveNoDifferences() throws Exception {
        DiffResult result = runDiff(
                mysqlAdapter, "consilens_demo", "cross_db_test",
                oracleAdapter, "SYSTEM", "CROSS_DB_TEST",
                "id", "ID"
        );
        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
    }

    private DiffResult runDiff(DatabaseAdapter srcAdapter, String srcSchema, String srcTable,
                                DatabaseAdapter tgtAdapter, String tgtSchema, String tgtTable,
                                String srcKey, String tgtKey) throws Exception {
        return runDiff(srcAdapter, TablePath.of(srcSchema, srcTable), tgtAdapter, TablePath.of(tgtSchema, tgtTable), srcKey, tgtKey);
    }

    /**
     * Creates a DatabaseAdapter without validation query (for Trino/Presto compatibility).
     */
    private static DatabaseAdapter createAdapterWithoutValidation(String name, String jdbcUrl,
                                                                  String username, String password,
                                                                  String connectorType) {
        com.consilens.connector.api.model.PoolConfiguration poolConfig = new com.consilens.connector.api.model.PoolConfiguration();
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        poolConfig.setConnectorType(connectorType);
        poolConfig.setMaxPoolSize(5);
        poolConfig.setMinIdle(1);
        poolConfig.setConnectionTimeout(10000);
        // No validation query - Trino/Presto don't support SELECT 1
        poolConfig.setValidationQuery(null);

        com.consilens.core.database.connection.ConnectionPool pool =
                com.consilens.core.database.connection.ConnectionPoolFactory.createPool(
                        jdbcUrl, username, password, connectorType, poolConfig);
        com.consilens.connector.api.DatabaseDialect dialect =
                com.consilens.core.database.dialect.DialectFactory.getDialect(connectorType);
        return new com.consilens.core.database.adpter.DefaultDatabaseAdapter(
                name, pool, dialect, jdbcUrl, ChecksumAlgorithm.CONCAT);
    }

    /**
     * Creates a key list with proper Integer type for cross-database compatibility.
     * Trino/Presto require integer literals for integer columns.
     */
    private static List<Object> createKeyList(int value) {
        List<Object> list = new java.util.ArrayList<>();
        list.add(Integer.valueOf(value));
        return list;
    }

    private DiffResult runDiff(DatabaseAdapter srcAdapter, TablePath srcPath,
                                DatabaseAdapter tgtAdapter, TablePath tgtPath,
                                String srcKey, String tgtKey) throws Exception {
        return runDiff(srcAdapter, srcPath, tgtAdapter, tgtPath, srcKey, tgtKey,
                Arrays.asList("name", "value", "status"), Arrays.asList("NAME", "VALUE", "STATUS"));
    }

    private DiffResult runDiff(DatabaseAdapter srcAdapter, TablePath srcPath,
                                DatabaseAdapter tgtAdapter, TablePath tgtPath,
                                String srcKey, String tgtKey,
                                List<String> srcExtraCols, List<String> tgtExtraCols) throws Exception {
        DifferConfig config = new DifferConfig(4, 100, ChecksumAlgorithm.CONCAT);

        TableSegment seg1 = TableSegment.builder()
                .database(srcAdapter)
                .tablePath(srcPath)
                .keyColumns(Arrays.asList(srcKey))
                .extraColumns(srcExtraCols)
                .minKey(Optional.of(createKeyList(1)))
                .maxKey(Optional.of(createKeyList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(tgtAdapter)
                .tablePath(tgtPath)
                .keyColumns(Arrays.asList(tgtKey))
                .extraColumns(tgtExtraCols)
                .minKey(Optional.of(createKeyList(1)))
                .maxKey(Optional.of(createKeyList(11)))
                .build();

        try (ChecksumDiffer differ = new ChecksumDiffer(config)) {
            return differ.diffTables(seg1, seg2).get(60, TimeUnit.SECONDS);
        }
    }
}
