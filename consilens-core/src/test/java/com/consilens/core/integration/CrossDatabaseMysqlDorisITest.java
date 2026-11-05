package com.consilens.core.integration;

import com.consilens.connector.api.model.TablePath;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.segment.TableSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-database (MySQL → Doris) Checksum diff integration test.
 * Doris uses MySQL protocol but has specific syntax for CREATE TABLE.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("跨数据库 MySQL vs Doris Checksum Diff 集成测试")
class CrossDatabaseMysqlDorisITest {

    /**
     * Doris 的 FE/BE 必须互相知道 IP，所以这里要固定地址；但子网不能写死：
     * 本机 data-lab 的 hive-net(172.30.0.0/16)、impala-net(172.31.0.0/16) 等已经占了不少网段，
     * 写死 172.30.0.0/24 会直接 "invalid pool request: Pool overlaps"。
     *
     * <p>做法：向 Docker 探测一个能创建成功的 /24（创建后立即删除），再把它用于正式网络；
     * 需要固定时用 -Ddoris.it.subnet=... 或环境变量 DORIS_IT_SUBNET 覆盖。
     */
    private static final String SUBNET = pickSubnet();
    private static final String DORIS_FE_IP = ipInSubnet(2);
    private static final String DORIS_BE_IP = ipInSubnet(3);

    private static final Network DORIS_NETWORK = Network.builder()
            .createNetworkCmdModifier(command -> command.withIpam(
                    new com.github.dockerjava.api.model.Network.Ipam()
                            .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config()
                                    .withSubnet(SUBNET))))
            .build();

    private static String pickSubnet() {
        String override = System.getProperty("doris.it.subnet");
        if (override == null || override.isBlank()) {
            override = System.getenv("DORIS_IT_SUBNET");
        }
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        java.util.Random random = new java.util.Random();
        try {
            var client = org.testcontainers.DockerClientFactory.instance().client();
            for (int attempt = 0; attempt < 25; attempt++) {
                String candidate = "10." + (180 + random.nextInt(60)) + "."
                        + (1 + random.nextInt(250)) + ".0/24";
                String name = "doris-it-probe-" + System.nanoTime();
                try {
                    var response = client.createNetworkCmd()
                            .withName(name)
                            .withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                                    .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config()
                                            .withSubnet(candidate)))
                            .exec();
                    client.removeNetworkCmd(response.getId()).exec();
                    return candidate;
                } catch (RuntimeException clashOrError) {
                    // 该网段被占用：换一个再试
                }
            }
        } catch (RuntimeException ignored) {
            // 连不上 Docker 时不阻塞：真正的 Docker 缺失由 @Testcontainers 处理
        }
        return "10.213.7.0/24";
    }

    private static String ipInSubnet(int host) {
        String base = SUBNET.substring(0, SUBNET.indexOf('/'));
        return base.substring(0, base.lastIndexOf('.')) + "." + host;
    }

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("consilens_demo")
            .withUsername("test")
            .withPassword("test123")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    private static final GenericContainer<?> DORIS_FE = new GenericContainer<>(
            // 使用与本地 compose 一致的 selectdb 镜像和 init_fe.sh 入口；
            // apache/doris:fe-latest 的默认入口在 arm64 上无法稳定完成 FE 启动。
            DockerImageName.parse("selectdb/doris.fe-ubuntu:2.1.11"))
            .withNetwork(DORIS_NETWORK)
            .withNetworkAliases("doris-fe")
            .withCreateContainerCmdModifier(command -> {
                command.withIpv4Address(DORIS_FE_IP);
                command.withEntrypoint("/bin/bash", "/usr/local/bin/init_fe.sh");
            })
            .withEnv("BUILD_TYPE", "k8s")
            .withExposedPorts(9030, 8030)
            .waitingFor(Wait.forHttp("/api/bootstrap").forPort(8030)
                    .withStartupTimeout(Duration.ofMinutes(10)));

    @Container
    private static final GenericContainer<?> DORIS_BE = new GenericContainer<>(
            DockerImageName.parse("selectdb/doris.be-ubuntu:2.1.11"))
            .withNetwork(DORIS_NETWORK)
            .withNetworkAliases("doris-be")
            .withCreateContainerCmdModifier(command -> {
                command.withIpv4Address(DORIS_BE_IP);
                command.withPrivileged(true);
                // 镜像的 entry_point.sh 才会把 FE_SERVERS/BE_ADDR 写进 be.conf；
                // 直接调 start_be.sh 会跳过配置，BE 永远不会注册到 FE。
                command.withEntrypoint("/bin/bash");
            })
            .withEnv("FE_SERVERS", "fe1:" + DORIS_FE_IP + ":9010")
            .withEnv("BE_ADDR", DORIS_BE_IP + ":9050")
            .withExposedPorts(8040)
            .dependsOn(DORIS_FE)
            // 容器内能看到宿主机 swap；start_be.sh 会因此拒绝启动，测试环境绕过该检查。
            .withCommand("-c", "sed -i 's/swapon -s | wc -l/0/' /opt/apache-doris/be/bin/start_be.sh; "
                    + "exec bash entry_point.sh")
            .waitingFor(Wait.forHttp("/api/health").forPort(8040)
                    .withStartupTimeout(Duration.ofMinutes(10)));

    private static DatabaseAdapter mysqlAdapter;
    private static DatabaseAdapter dorisAdapter;

    @BeforeAll
    static void setUp() throws Exception {
        mysqlAdapter = CrossDatabaseITestBase.createAdapter("mysql-source", MYSQL, "mysql");

        String dorisHost = DORIS_FE.getHost();
        Integer dorisPort = DORIS_FE.getMappedPort(9030);

        // First create database on Doris (connect without database)
        String dorisRootUrl = "jdbc:mysql://" + dorisHost + ":" + dorisPort + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC";
        DatabaseAdapter dorisRootAdapter = CrossDatabaseITestBase.createAdapter("doris-root", dorisRootUrl, "root", "", "doris");
        waitForDorisBackend(dorisRootAdapter);
        CrossDatabaseITestBase.executeSql(dorisRootAdapter, "CREATE DATABASE IF NOT EXISTS consilens_demo");
        dorisRootAdapter.close();

        // Now connect with database
        String dorisUrl = "jdbc:mysql://" + dorisHost + ":" + dorisPort + "/consilens_demo?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC";
        dorisAdapter = CrossDatabaseITestBase.createAdapter("doris-target", dorisUrl, "root", "", "doris");
        waitForDorisStorage(dorisAdapter);

        createTestTable(mysqlAdapter, "cross_source");
        createTestTable(dorisAdapter, "cross_target");

        insertTestData(mysqlAdapter, "cross_source");
        insertTestData(dorisAdapter, "cross_target");
    }

    @AfterAll
    static void tearDown() {
        if (mysqlAdapter != null) mysqlAdapter.close();
        if (dorisAdapter != null) dorisAdapter.close();
    }

    private static void waitForDorisBackend(DatabaseAdapter adapter) throws Exception {
        // BE 容器 healthcheck 通过不代表已经注册到 FE；建表前必须等 Alive=true。
        for (int i = 0; i < 60; i++) {
            List<Object[]> rows = adapter.query("SHOW BACKENDS", Object[].class);
            boolean alive = rows.stream().anyMatch(row -> Arrays.stream(row)
                    .anyMatch(v -> "true".equalsIgnoreCase(String.valueOf(v))));
            if (alive) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Doris BE not alive after 120s");
    }

    /**
     * BE 注册（Alive=true）之后还要等它的存储盘就绪，否则建表会报
     * "Failed to find enough backend ... disks count={}"。
     * 这里直接用一次“建空表 + 删表”探针判断，比解析 SHOW BACKENDS 的列序更稳。
     */
    private static void waitForDorisStorage(DatabaseAdapter adapter) throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS storage_probe (id INT) ENGINE=OLAP "
                + "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 "
                + "PROPERTIES (\"replication_num\" = \"1\")";
        Exception last = null;
        for (int i = 0; i < 90; i++) {
            try {
                CrossDatabaseITestBase.executeSql(adapter, ddl);
                CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS storage_probe");
                return;
            } catch (Exception exception) {
                last = exception;
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("Doris backend storage not ready after 180s", last);
    }

    private static void createTestTable(DatabaseAdapter adapter, String tableName) throws Exception {
        String dialect = adapter.getConnectorType();
        if ("doris".equalsIgnoreCase(dialect)) {
            // Doris uses MySQL protocol but requires ENGINE=OLAP and DUPLICATE KEY
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at DATETIME, is_active BOOLEAN" +
                ") ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 " +
                "PROPERTIES (\"replication_num\" = \"1\")");
        } else {
            CrossDatabaseITestBase.executeSql(adapter, "DROP TABLE IF EXISTS " + tableName);
            CrossDatabaseITestBase.executeSql(adapter,
                "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, name VARCHAR(100), value DECIMAL(10,2), " +
                "status VARCHAR(20), created_at TIMESTAMP, is_active BOOLEAN)");
        }
    }

    private static void insertTestData(DatabaseAdapter adapter, String tableName) throws Exception {
        for (int i = 1; i <= 10; i++) {
            String sql = String.format(
                "INSERT INTO %s (id, name, value, status, created_at, is_active) VALUES (%d, 'item_%d', %s, 'active', '2026-01-01 00:00:00', %d)",
                tableName, i, i, String.valueOf(i * 10.50), i % 2);
            CrossDatabaseITestBase.executeSql(adapter, sql);
        }
    }

    @Test
    @Order(1)
    @DisplayName("MySQL 和 Doris 中相同数据应无差异")
    void identicalDataAcrossDatabasesShouldHaveNoDifferences() throws Exception {
        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(dorisAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(11)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isFalse();
        assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(10);
        assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(10);
    }

    @Test
    @Order(2)
    @DisplayName("MySQL vs Doris 应检测到数据差异")
    void shouldDetectDifferencesAcrossDatabases() throws Exception {
        CrossDatabaseITestBase.executeSql(dorisAdapter,
            "INSERT INTO cross_target (id, name, value, status, created_at, is_active) VALUES (11, 'extra_item', 99.99, 'active', '2026-01-01 00:00:00', 1)");

        TableSegment seg1 = TableSegment.builder()
                .database(mysqlAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_source"))
                .keyColumns(Arrays.asList("id"))
                .extraColumns(Arrays.asList("name", "value", "status"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        TableSegment seg2 = TableSegment.builder()
                .database(dorisAdapter)
                .tablePath(TablePath.of("consilens_demo", "cross_target"))
                .keyColumns(Arrays.asList("ID"))
                .extraColumns(Arrays.asList("NAME", "VALUE", "STATUS"))
                .minKey(Optional.of(Arrays.asList(1)))
                .maxKey(Optional.of(Arrays.asList(12)))
                .build();

        DiffResult result = CrossDatabaseITestBase.runChecksumDiff(seg1, seg2);

        assertThat(result).isNotNull();
        assertThat(result.hasDifferences()).isTrue();

        List<DiffRow> sourceMissing = result.getDifferences().stream()
                .filter(r -> r.getOperation() == DiffOperation.SOURCE_MISSING)
                .collect(Collectors.toList());
        assertThat(sourceMissing).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("MySQL 与 Doris 应对所有公共类型和四类结果给出精确结论")
    void shouldVerifyPublicTypeFamiliesAndEveryDiffDirection() throws Exception {
        CrossDatabaseAccuracyFixture.verify(
                mysqlAdapter, TablePath.of("consilens_demo", "placeholder"),
                dorisAdapter, TablePath.of("consilens_demo", "placeholder"));
    }
}
