package com.consilens.server.application.capability;

import com.consilens.server.support.crypto.SecretProtectorTestKeys;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ComparisonConfig;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunTaskHandlerTest {
    /** 统一形状（CLI）：connection + resource；keys 成对。 */
    private static java.util.Map<String, Object> cliEndpoint(String type, String table,
                                                             Long datasourceId, String database) {
        java.util.Map<String, Object> connection = new java.util.LinkedHashMap<>();
        if (datasourceId != null) {
            connection.put("datasourceId", datasourceId);
        }
        if (database != null) {
            connection.put("database", database);
        }
        return java.util.Map.of("type", type, "connection", connection,
                "resource", java.util.Map.of("type", "table", "name", table));
    }

    /** 统一形状（CLI）：comparison.keys 成对。 */
    private static java.util.Map<String, Object> cliKeys(String key) {
        return java.util.Map.of("keys",
                java.util.Map.of("source", java.util.List.of(key), "target", java.util.List.of(key)));
    }


    @Test
    void missingDatasourceFailsLoudlyDuringInjection() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        when(dataSourceRepository.findById(404L)).thenReturn(java.util.Optional.empty());
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(null, configService, null, dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", cliEndpoint("mysql", "t", 404L, null),
                        "target", cliEndpoint("mysql", "t", 404L, null),
                        "comparison", cliKeys("id")));

        assertThrows(com.consilens.server.domain.exception.ResourceNotFoundException.class,
                () -> handler.injectConnections(config));
    }

    @Test
    void shouldRevealEncryptedSinkPasswordBeforeOpeningSinks() throws Exception {
        var protector = SecretProtectorTestKeys.protector();
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                protector,
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        SinkConfig sink = new SinkConfig();
        sink.setFormat("table");
        sink.setType("result");
        sink.setProperties(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "url", "jdbc:mysql://127.0.0.1:3306/dq",
                "username", "sink-user",
                "password", protector.protect("sink-secret"))));
        ServerCompareConfig config = ServerCompareConfig.builder()
                .result(ResultConfig.builder().sinks(List.of(sink)).build())
                .build();

        handler.injectSinkSecrets(config);

        com.fasterxml.jackson.databind.JsonNode properties = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(config.getResult().getSinks().get(0).getProperties());
        assertEquals("sink-secret", properties.path("password").asText());
    }

    /**
     * 差异行的取值随 artifact 落盘：diff-report 的 samples 从同一行读 sourceValues/targetValues
     * 渲染旧值/新值（DiffReportServiceImpl），缺少这两键时 UI 只能显示主键。
     * artifact 为服务端本地文件，取值经 /v1 报告接口暴露的粒度不变。
     */
    @Test
    void shouldPersistDiffRowValuesForReportSamples() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("source-value"),
                List.of("target-value"),
                List.of("name"),
                List.of("name"));

        Map<String, Object> result = handler.diffRow(row);

        assertThat(result).containsKeys("operation", "primaryKey", "metadata", "columnNames",
                "sourceValues", "targetValues");
        assertThat(result.get("sourceValues")).isEqualTo(List.of("source-value"));
        assertThat(result.get("targetValues")).isEqualTo(List.of("target-value"));
    }

    /**
     * 一侧缺失的行只在另一侧带列名（columnNames1 是空集合而非 null），
     * columnNames 必须取到有值的那一侧，否则 UI 拿到空数组无法标注字段名。
     */
    @Test
    void shouldResolveColumnNamesFromThePopulatedSide() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        Map<String, Object> sourceMissing = handler.diffRow(
                DiffRow.added(List.of(7), List.of("target-value"), List.of("name")));
        Map<String, Object> targetMissing = handler.diffRow(
                DiffRow.removed(List.of(7), List.of("source-value"), List.of("name")));

        assertThat(sourceMissing.get("columnNames")).isEqualTo(List.of("name"));
        assertThat(targetMissing.get("columnNames")).isEqualTo(List.of("name"));
    }

    @Test
    void shouldPassOracleSidIntoJdbcUrl() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.artifact.ArtifactService artifactService =
                mock(com.consilens.server.application.artifact.ArtifactService.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        com.consilens.server.domain.model.DataSourceRecord ds = com.consilens.server.domain.model.DataSourceRecord.builder()
                .id(6L).name("ds-6").type("oracle")
                .paramJson("{\"host\":\"10.0.0.6\",\"port\":1521,\"sid\":\"ORCLPDB1\","
                        + "\"username\":\"system\",\"password\":\""
                        + SecretProtectorTestKeys.protector().protect("p@ss") + "\"}")
                .build();
        when(dataSourceRepository.findById(6L)).thenReturn(java.util.Optional.of(ds));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "goal", "check",
                        "source", cliEndpoint("oracle", "EMP", 6L, null),
                        "target", cliEndpoint("oracle", "EMP", 6L, null),
                        "comparison", cliKeys("EMPNO")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("url"))
                .isEqualTo("jdbc:oracle:thin:@//10.0.0.6:1521/ORCLPDB1");
        assertThat(config.getTarget().getConnection().get("url"))
                .isEqualTo("jdbc:oracle:thin:@//10.0.0.6:1521/ORCLPDB1");
    }

    @Test
    void shouldInjectConnectionFromDatasource() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.artifact.ArtifactService artifactService =
                mock(com.consilens.server.application.artifact.ArtifactService.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        com.consilens.server.domain.model.DataSourceRecord ds = com.consilens.server.domain.model.DataSourceRecord.builder()
                .id(5L).name("ds-5").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"root\",\"properties\":\"useSSL=false&socketTimeout=1000\","
                        + "\"password\":\""
                        + SecretProtectorTestKeys.protector().protect("p@ss") + "\"}")
                .build();
        when(dataSourceRepository.findById(5L)).thenReturn(java.util.Optional.of(ds));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "goal", "check",
                        "source", cliEndpoint("mysql", "orders", 5L, null),
                        "target", cliEndpoint("mysql", "orders", 5L, null),
                        "comparison", cliKeys("id")));

        handler.injectConnections(config);

        java.util.Map<String, Object> connection = config.getSource().getConnection();
        assertThat(connection.get("url")).isEqualTo("jdbc:mysql://10.0.0.5:3306/orders");
        assertThat(connection.get("password")).isEqualTo("p@ss");
        assertThat(connection.get("useSSL")).isEqualTo("false");
        assertThat(connection.get("socketTimeout")).isEqualTo("1000");
        assertThat(connection).doesNotContainKey("properties");
        assertThat(config.getTarget().getConnection().get("url")).isEqualTo("jdbc:mysql://10.0.0.5:3306/orders");
    }

    @Test
    void shouldPreferEndpointDatabaseOverDatasourceParam() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(
                mock(com.consilens.server.application.artifact.ArtifactService.class),
                configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        when(dataSourceRepository.findById(5L)).thenReturn(java.util.Optional.of(
                com.consilens.server.domain.model.DataSourceRecord.builder()
                        .id(5L).type("mysql")
                        .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"param-db\"}")
                        .build()));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", cliEndpoint("mysql", "t", 5L, "endpoint-db"),
                        "target", cliEndpoint("mysql", "t", null, null),
                        "comparison", cliKeys("id")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("url"))
                .isEqualTo("jdbc:mysql://10.0.0.5:3306/endpoint-db");
    }

    @Test
    void shouldRevealEncryptedDirectConnectionPassword() {
        com.consilens.server.support.crypto.AesGcmSecretProtector crypto =
                SecretProtectorTestKeys.protector();
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(
                mock(com.consilens.server.application.artifact.ArtifactService.class),
                configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                crypto,
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", java.util.Map.of("type", "mysql",
                                "connection", java.util.Map.of(
                                        "url", "jdbc:mysql://10.0.0.5:3306/db",
                                        "password", crypto.protect("real-pass")),
                                "resource", java.util.Map.of("type", "table", "name", "t")),
                        "target", cliEndpoint("mysql", "t", null, null),
                        "comparison", cliKeys("id")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("password")).isEqualTo("real-pass");
    }

    @Test
    void shouldBoundDiffRowsInResultArtifact() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        List<DiffRow> rows = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            rows.add(DiffRow.added(List.of(i), List.of("v" + i), List.of("id", "value")));
        }
        DiffResult diffResult = DiffResult.builder()
                .differences(rows)
                .completedAt(java.time.Instant.now())
                .build();

        Map<String, Object> content = handler.runResult(diffResult);

        assertThat(content.get("differenceCount")).isEqualTo(2500L);
        assertThat(content.get("resultSchemaVersion"))
                .isEqualTo(DiffResult.RESULT_SCHEMA_VERSION);
        assertThat(content.get("differenceSampleSize")).isEqualTo(1000);
        assertThat(content.get("differenceSampleTruncated")).isEqualTo(Boolean.TRUE);
        assertThat((List<?>) content.get("differences")).hasSize(1000);
    }

    @Test
    void shouldPublishServerRunResultsThroughLifecycleBeforeWritingArtifact() {
        ArtifactService artifactService = mock(ArtifactService.class);
        ServerCompareConfigService configService = mock(ServerCompareConfigService.class);
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigArtifactId("config-1");
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder().type("mysql").table("source_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/source")).build())
                .target(EndpointConfig.builder().type("mysql").table("target_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/target")).build())
                .keys(List.of("id"))
                .comparison(ComparisonConfig.builder().fields(List.of("amount")).build())
                .build();
        DiffResult result = DiffResult.builder()
                .differences(List.of(DiffRow.added(List.of(1), List.of("1"), List.of("id"))))
                .completedAt(java.time.Instant.now())
                .build();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService,
                        mock(com.consilens.server.domain.repository.TaskRepository.class),
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore()) {
            @Override
            protected CompareRuntime createCompareRuntime() {
                return compareRequest -> result;
            }

            @Override
            protected DiffLifecycle buildLifecycle(ServerCompareConfig ignored, String taskId) {
                return lifecycle;
            }
        };
        when(configService.fromRunRequest(request)).thenReturn(config);
        when(configService.toCompareRequest(any(), any())).thenReturn(mock(CompareRequest.class));
        when(artifactService.writeArtifact(any(), any(), any(), any(), any()))
                .thenReturn(ArtifactRefDto.builder().id("run-result").type("RUN_RESULT").format("json").build());

        handler.handle(TaskExecutionContext.builder().taskId(1L).instanceKey("task-1").build(), request);

        assertThat(lifecycle.started).isTrue();
        assertThat(lifecycle.publishedDifferences).isTrue();
        assertThat(lifecycle.completed).isTrue();
        assertThat(lifecycle.closed).isTrue();
    }

    @Test
    void shouldWriteConfiguredResultAndDiffRecordTableSinks() throws Exception {
        String url = "jdbc:h2:mem:server_run_sinks;MODE=MySQL;DB_CLOSE_DELAY=-1";
        ResultConfig resultConfig = new ResultConfig();
        resultConfig.setSinks(List.of(tableSink("result", url, "dv_job_execution_result", "["
                        + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                        + "{\"name\":\"run_status\",\"value\":\"${status}\",\"columnType\":\"VARCHAR(32)\"}]"),
                tableSink("diff-record", url, "dv_actual_values", "["
                        + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                        + "{\"name\":\"operation\",\"value\":\"${operation}\",\"columnType\":\"VARCHAR(32)\"}]")));
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        handler.handle(context(), request());

        assertThat(rowCount(url, "dv_job_execution_result")).isEqualTo(1);
        assertThat(rowCount(url, "dv_actual_values")).isEqualTo(1);
    }

    @Test
    void shouldFailRunWhenSinkCannotOpenAndFailOnSinkErrorIsTrue() {
        ResultConfig resultConfig = invalidSinkConfig(true);
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        assertThrows(CapabilityExecutionException.class, () -> handler.handle(context(), request()));
    }

    @Test
    void shouldContinueRunWhenSinkCannotOpenAndFailOnSinkErrorIsFalse() {
        ResultConfig resultConfig = invalidSinkConfig(false);
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        assertDoesNotThrow(() -> handler.handle(context(), request()));
    }

    private RunTaskHandler handlerWithResultConfig(ResultConfig resultConfig, DiffResult result) {
        ArtifactService artifactService = mock(ArtifactService.class);
        ServerCompareConfigService configService = mock(ServerCompareConfigService.class);
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder().type("mysql").table("source_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/source")).build())
                .target(EndpointConfig.builder().type("mysql").table("target_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/target")).build())
                .keys(List.of("id"))
                .comparison(ComparisonConfig.builder().fields(List.of("amount")).build())
                .result(resultConfig)
                .build();
        when(configService.fromRunRequest(any())).thenReturn(config);
        when(configService.toCompareRequest(any(), any())).thenReturn(mock(CompareRequest.class));
        when(artifactService.writeArtifact(any(), any(), any(), any(), any()))
                .thenReturn(ArtifactRefDto.builder().id("run-result").type("RUN_RESULT").format("json").build());
        return new RunTaskHandler(artifactService, configService,
                        mock(com.consilens.server.domain.repository.TaskRepository.class),
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore()) {
            @Override
            protected CompareRuntime createCompareRuntime() {
                return compareRequest -> result;
            }
        };
    }

    /**
     * 差异明细落盘必须指向默认路径：用户自配的 jsonl sink 即使格式匹配也只是另写一份文件，
     * 不能顶替报告读取的那个默认文件（否则报告退化成 1000 行内联样本）。
     */
    @Test
    void shouldAppendDefaultDiffRecordSinkWhenUserJsonlSinkTargetsElsewhere() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        SinkConfig userJsonl = new SinkConfig();
        userJsonl.setFormat("jsonl");
        userJsonl.setType("diff-record");
        userJsonl.setProperties("{\"path\":\"./custom/diff-record.jsonl\"}");
        ServerCompareConfig config = ServerCompareConfig.builder()
                .result(ResultConfig.builder().sinks(List.of(userJsonl)).build())
                .build();

        List<SinkConfig> sinks = handler.composeSinks(config);

        assertEquals(2, sinks.stream().filter(sink -> "jsonl".equals(sink.getFormat())).count());
        assertThat(sinks).anySatisfy(sink -> {
            try {
                assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(sink.getProperties()).get("path").asText())
                        .endsWith("/.consilens-server/artifacts/run-result-${taskId}.jsonl");
            } catch (Exception exception) {
                throw new AssertionError("cannot parse sink properties", exception);
            }
        });
    }

    /**
     * 用户自配的 jsonl sink 指向默认路径时只写一份，避免同一文件被两个 sink 同时打开。
     */
    @Test
    void shouldNotDuplicateDefaultDiffRecordSinkWhenUserTargetsSamePath() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());
        SinkConfig userJsonl = new SinkConfig();
        userJsonl.setFormat("jsonl");
        userJsonl.setType("diff-record");
        userJsonl.setProperties("{\"path\":\"./.consilens-server/artifacts/run-result-${taskId}.jsonl\"}");
        ServerCompareConfig config = ServerCompareConfig.builder()
                .result(ResultConfig.builder().sinks(List.of(userJsonl)).build())
                .build();

        List<SinkConfig> sinks = handler.composeSinks(config);

        assertEquals(1, sinks.stream().filter(sink -> "jsonl".equals(sink.getFormat())).count());
    }

    private ResultConfig invalidSinkConfig(boolean failOnSinkError) {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("missing");
        sinkConfig.setType("result");
        ResultConfig resultConfig = new ResultConfig();
        resultConfig.setFailOnSinkError(failOnSinkError);
        resultConfig.setSinks(List.of(sinkConfig));
        return resultConfig;
    }

    private SinkConfig tableSink(String type, String url, String tableName, String columns) {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType(type);
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"" + tableName + "\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"columns\":" + columns
                + "}");
        return sinkConfig;
    }

    private DiffResult resultWithDifference() {
        return DiffResult.builder()
                .differences(List.of(DiffRow.added(List.of(1), List.of("1"), List.of("id"))))
                .completedAt(java.time.Instant.now())
                .build();
    }

    private TaskExecutionContext context() {
        return TaskExecutionContext.builder().taskId(1L).instanceKey("task-1").build();
    }

    private RunRequest request() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigArtifactId("config-1");
        return request;
    }

    private int rowCount(String url, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static class RecordingLifecycle implements DiffLifecycle {

        private boolean started;
        private boolean publishedDifferences;
        private boolean completed;
        private boolean closed;

        @Override
        public void onDiffStart(DiffContext context) {
            started = true;
        }

        @Override
        public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) {
            publishedDifferences = true;
        }

        @Override
        public void onDiffComplete(DiffResult result, DiffContext context) {
            completed = true;
        }

        @Override
        public void onDiffError(DiffContext context, Throwable error) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static com.consilens.server.infrastructure.log.RunLogStore runLogStore() {
        return new com.consilens.server.infrastructure.log.RunLogStore(
                java.nio.file.Paths.get("target/test-run-logs"), false);
    }

    @Test
    void attemptScopedIdSeparatesRetriesOfTheSameInstance() {
        TaskExecutionContext first = TaskExecutionContext.builder()
                .instanceKey("inst_1")
                .traceId("trace_aaaaaaaaaaaa1111")
                .build();
        TaskExecutionContext retry = TaskExecutionContext.builder()
                .instanceKey("inst_1")
                .traceId("trace_bbbbbbbbbbbb2222")
                .build();
        TaskExecutionContext noTrace = TaskExecutionContext.builder()
                .instanceKey("inst_1")
                .build();

        assertEquals("inst_1-trace_aaaaaa", RunTaskHandler.attemptScopedId(first));
        assertEquals("inst_1-trace_bbbbbb", RunTaskHandler.attemptScopedId(retry));
        assertEquals("inst_1", RunTaskHandler.attemptScopedId(noTrace));
    }

    @Test
    void parsesClusterStatisticsFromDiagnostics() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null, null,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        Map<String, Object> statistics = handler.parseClusterStatistics(
                "{\"submissionId\":\"s-1\",\"statistics\":{\"sourceRowCount\":100,"
                        + "\"targetRowCount\":100,\"totalDifferences\":3}}");

        assertEquals(3, ((Number) statistics.get("totalDifferences")).intValue());
        assertEquals(100, ((Number) statistics.get("sourceRowCount")).intValue());
    }

    @Test
    void returnsEmptyStatisticsWhenDiagnosticsIsNotASummary() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null, null,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClusterSubmitSupport(new ClusterSubmitterLocator()), runLogStore());

        assertThat(handler.parseClusterStatistics(null)).isEmpty();
        assertThat(handler.parseClusterStatistics("not-json")).isEmpty();
        assertThat(handler.parseClusterStatistics("{\"submissionId\":\"s-1\"}")).isEmpty();
    }

    @Test
    void deterministicFailuresAreNotRetryable() {
        // 语法错/权限/约束/缺表：重跑一遍结论还是不变，不该占用集群重试
        assertThat(RunTaskHandler.isRetryable(new java.sql.SQLSyntaxErrorException("bad sql",
                "42000"))).isFalse();
        assertThat(RunTaskHandler.isRetryable(new java.sql.SQLIntegrityConstraintViolationException(
                "dup key", "23000"))).isFalse();
        assertThat(RunTaskHandler.isRetryable(new IllegalArgumentException("bad config"))).isFalse();
        assertThat(RunTaskHandler.isRetryable(new RuntimeException("wrapped",
                new java.sql.SQLException("access denied", "42501")))).isFalse();
        // 连接中断/超时仍然可重试
        assertThat(RunTaskHandler.isRetryable(new java.sql.SQLException("connection reset", "08S01")))
                .isTrue();
        assertThat(RunTaskHandler.isRetryable(new RuntimeException("timeout"))).isTrue();
    }
    @Test
    void rootCauseMessageIsAppendedSoCallersSeeActionableHint() {
        RuntimeException wrapped = new RuntimeException("Table diff failed",
                new IllegalArgumentException("Key column 'k1' is nullable; set allowNullableKeys=true"));

        assertThat(RunTaskHandler.withRootCauseMessage(wrapped, 2000))
                .isEqualTo("Table diff failed: Key column 'k1' is nullable; set allowNullableKeys=true");
    }

    @Test
    void rootCauseMessageIsNotDuplicatedOrInvented() {
        assertThat(RunTaskHandler.withRootCauseMessage(new RuntimeException("table not found"), 2000))
                .isEqualTo("table not found");

        RuntimeException alreadyIncluded = new RuntimeException("outer: inner detail",
                new IllegalStateException("inner detail"));
        assertThat(RunTaskHandler.withRootCauseMessage(alreadyIncluded, 2000))
                .isEqualTo("outer: inner detail");
    }

    @Test
    void rootCauseMessageIsTruncatedToConfiguredLimit() {
        RuntimeException longMessage = new RuntimeException("outer",
                new IllegalStateException("0123456789012345678901234567890123456789"));

        String message = RunTaskHandler.withRootCauseMessage(longMessage, 20);

        assertThat(message).hasSize(23).endsWith("...");
    }

}
