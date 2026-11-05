package com.consilens.core.integration;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Real accuracy contract for the official OceanBase CE image in MySQL mode.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs OceanBase 官方容器准确性集成测试")
class CrossDatabaseMysqlOceanBaseITest {

    private static final String DATABASE = "consilens_demo";
    private static final String PASSWORD = "test123";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DATABASE)
            .withUsername("test")
            .withPassword(PASSWORD)
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> OCEANBASE = new GenericContainer<>(
            DockerImageName.parse("oceanbase/oceanbase-ce:4.2.5.5-105000032025071717"))
            .withEnv("MODE", "slim")
            .withEnv("OB_TENANT_PASSWORD", PASSWORD)
            .withExposedPorts(2881)
            .waitingFor(new LogMessageWaitStrategy()
                    .withRegEx(".*boot success!.*\\s")
                    .withStartupTimeout(Duration.ofMinutes(10)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter oceanBaseAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        String rootUrl = jdbcUrl("");
        DatabaseAdapter rootAdapter = CrossDatabaseITestBase.createAdapter(
                "oceanbase-root", rootUrl, "root@test", PASSWORD, "oceanbase");
        try {
            CrossDatabaseITestBase.executeSql(rootAdapter, "CREATE DATABASE IF NOT EXISTS " + DATABASE);
        } finally {
            rootAdapter.close();
        }
        oceanBaseAdapter = CrossDatabaseITestBase.createAdapter(
                "oceanbase-target", jdbcUrl(DATABASE), "root@test", PASSWORD, "oceanbase");
        CrossDatabaseITestBase.executeSql(oceanBaseAdapter, "SET time_zone = '+00:00'");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) {
            mysqlAdapter.close();
        }
        if (oceanBaseAdapter != null) {
            oceanBaseAdapter.close();
        }
    }

    @Test
    @DisplayName("MySQL 与 OceanBase 应对公共类型和全部差异方向给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of(DATABASE, "placeholder"),
                oceanBaseAdapter, TablePath.of(DATABASE, "placeholder"));
    }

    private static String jdbcUrl(String database) {
        return "jdbc:mysql://" + OCEANBASE.getHost() + ":" + OCEANBASE.getMappedPort(2881) + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC"
                + "&allowPublicKeyRetrieval=true";
    }
}
