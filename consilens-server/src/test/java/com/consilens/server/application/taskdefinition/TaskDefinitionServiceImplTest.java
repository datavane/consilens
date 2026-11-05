package com.consilens.server.application.taskdefinition;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.application.capability.config.TaskDefinitionConfigValidator;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTaskDefinitionServiceTest {
    /** 统一形状（CLI）：connection + resource。 */
    private static Map<String, Object> cliEndpoint(String type, String table,
                                                   Map<String, Object> connection) {
        return Map.of("type", type, "connection", connection,
                "resource", Map.of("type", "table", "name", table));
    }

    /** 统一形状（CLI）：comparison.keys 成对。 */
    private static Map<String, Object> cliKeys(String key) {
        return Map.of("keys", Map.of("source", List.of(key), "target", List.of(key)));
    }


    private static final Long DEF_ID = 1L;

    private TaskDefinitionRepository definitionRepository;
    private TaskRepository taskRepository;
    private DataSourceRepository dataSourceRepository;
    private RunTaskSubmissionService submissionService;
    private TaskDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        definitionRepository = mock(TaskDefinitionRepository.class);
        taskRepository = mock(TaskRepository.class);
        dataSourceRepository = mock(DataSourceRepository.class);
        submissionService = mock(RunTaskSubmissionService.class);
        service = new TaskDefinitionServiceImpl(definitionRepository, taskRepository,
                submissionService,
                new ServerCompareConfigService(mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new ObjectMapper()),
                mock(TaskDefinitionConfigValidator.class),
                new ObjectMapper(),
                com.consilens.server.support.crypto.SecretProtectorTestKeys.protector());
    }

    @Test
    void shouldCreateDefinition() {
        when(definitionRepository.save(any())).thenAnswer(invocation -> {
            TaskDefinitionRecord record = invocation.getArgument(0);
            record.setId(DEF_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });

        TaskDefinitionDto dto = service.create(validRequest());

        assertEquals("orders-check", dto.getName());
        assertEquals(true, dto.getEnabled());
        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        assertTrue(captor.getValue().getDefinitionKey().startsWith("taskdef_"));
        assertTrue(captor.getValue().getConfig().contains("\"keys\""));
    }

    @Test
    void shouldRejectDuplicateName() {
        when(definitionRepository.findByName("orders-check"))
                .thenReturn(Optional.of(TaskDefinitionRecord.builder().id(9L).build()));

        assertThrows(IllegalArgumentException.class, () -> service.create(validRequest()));
    }

    @Test
    void shouldMapConcurrentDuplicateNameToInputError() {
        when(definitionRepository.save(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_cs_task_definition_name"));

        assertThrows(IllegalArgumentException.class, () -> service.create(validRequest()));
    }

    @Test
    void shouldMaskLegacyInlinePasswordOnGet() {
        TaskDefinitionRecord record = TaskDefinitionRecord.builder()
                .id(DEF_ID).name("legacy").taskType("RUN").enabled(true)
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\","
                        + "\"connection\":{\"url\":\"jdbc:mysql://x\",\"password\":\"secret\"}},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        when(taskRepository.listByDefinitionId(eq(DEF_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        TaskDefinitionDetailDto detail = service.get(DEF_ID);

        Map<?, ?> source = (Map<?, ?>) detail.getConfig().get("source");
        Map<?, ?> connection = (Map<?, ?>) source.get("connection");
        assertEquals("******", connection.get("password"));
    }

    @Test
    void shouldMaskLegacySensitivePropertiesOnGet() {
        TaskDefinitionRecord record = TaskDefinitionRecord.builder()
                .id(DEF_ID).name("legacy-props").taskType("RUN").enabled(true)
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\","
                        + "\"connection\":{\"url\":\"jdbc:mysql://x\","
                        + "\"properties\":\"useSSL=false&password=secret&token=abc\"}},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        when(taskRepository.listByDefinitionId(eq(DEF_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        TaskDefinitionDetailDto detail = service.get(DEF_ID);

        Map<?, ?> source = (Map<?, ?>) detail.getConfig().get("source");
        Map<?, ?> connection = (Map<?, ?>) source.get("connection");
        String properties = String.valueOf(connection.get("properties"));
        assertTrue(properties.contains("password=******"), properties);
        assertTrue(properties.contains("token=******"), properties);
        assertTrue(!properties.contains("secret"), properties);
    }

    @Test
    void shouldStripMaskedSensitivePropertiesOnUpdate() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record()));
        when(definitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> connection = new java.util.LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://x");
        connection.put("properties", "useSSL=false&password=******&ApplicationName=consilens");
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("type", "mysql");
        source.put("resource", Map.of("type", "table", "name", "orders"));
        source.put("connection", connection);
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of(
                "source", source,
                "target", cliEndpoint("mysql", "orders", Map.of()),
                "comparison", cliKeys("id")));

        service.update(DEF_ID, request);

        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        String saved = captor.getValue().getConfig();
        assertTrue(saved.contains("useSSL=false"), saved);
        assertTrue(saved.contains("ApplicationName=consilens"), saved);
        assertTrue(!saved.contains("******"), saved);
    }

    @Test
    void shouldStripMaskedInlinePasswordOnUpdate() {
        TaskDefinitionRecord existing = TaskDefinitionRecord.builder()
                .id(DEF_ID).name("legacy").taskType("RUN").enabled(true)
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\","
                        + "\"connection\":{\"url\":\"jdbc:mysql://x\",\"password\":\"secret\"}},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(existing));
        when(definitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> connection = new java.util.LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://x");
        connection.put("password", "******");
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("type", "mysql");
        source.put("resource", Map.of("type", "table", "name", "orders"));
        source.put("connection", connection);
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("source", source);
        config.put("target", cliEndpoint("mysql", "orders", Map.of()));
        config.put("comparison", cliKeys("id"));

        TaskDefinitionCreateRequest request = new TaskDefinitionCreateRequest();
        request.setName("legacy");
        request.setConfig(config);

        service.update(DEF_ID, request);

        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        assertTrue(!captor.getValue().getConfig().contains("password"),
                captor.getValue().getConfig());
    }

    @Test
    void shouldRejectInlineConnectionPassword() {
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of(
                "source", cliEndpoint("mysql", "orders",
                        Map.of("url", "jdbc:mysql://x", "password", "secret")),
                "target", cliEndpoint("mysql", "orders", Map.of()),
                "comparison", cliKeys("id")));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectSensitiveConnectionProperties() {
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of(
                "source", cliEndpoint("mysql", "orders", Map.of(
                        "url", "jdbc:mysql://x",
                        "properties", "useSSL=false&password=secret")),
                "target", cliEndpoint("mysql", "orders", Map.of()),
                "comparison", cliKeys("id")));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectInvalidConfig() {
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of("source", Map.of("type", "mysql")));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldGetDetailWithRecentInstances() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record()));
        when(taskRepository.listByDefinitionId(DEF_ID, 5)).thenReturn(List.of(
                TaskInstanceRecord.builder().instanceKey("inst_1").serialNo("s-1")
                        .status(TaskStatus.SUCCEEDED)
                        .submitTime(Instant.parse("2026-01-01T00:00:00Z")).build()));

        TaskDefinitionDetailDto detail = service.get(DEF_ID);

        assertEquals("orders-check", detail.getName());
        assertEquals(1, detail.getRecentInstances().size());
        assertEquals("inst_1", detail.getRecentInstances().get(0).getInstanceId());
        assertEquals("SUCCEEDED", detail.getRecentInstances().get(0).getStatus());
    }

    @Test
    void shouldRejectNonPositiveExecutionTimeout() {
        TaskDefinitionCreateRequest request = validRequest();
        Map<String, Object> config = new java.util.LinkedHashMap<>(request.getConfig());
        config.put("executionOptions", Map.of("timeoutMs", 0));
        request.setConfig(config);

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectUnknownExecutionPlatform() {
        TaskDefinitionCreateRequest request = validRequest();
        Map<String, Object> config = new java.util.LinkedHashMap<>(request.getConfig());
        config.put("platform", "spark");
        request.setConfig(config);

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectFractionalExecutionTimeout() {
        TaskDefinitionCreateRequest request = validRequest();
        Map<String, Object> config = new java.util.LinkedHashMap<>(request.getConfig());
        config.put("executionOptions", Map.of("timeoutMs", 1.5));
        request.setConfig(config);

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldApplyNumericStringExecutionTimeoutOnRun() {
        TaskDefinitionRecord definition = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"keys\":[\"id\"],\"executionOptions\":{\"timeoutMs\":\"1500\"}}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(definition));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), any());
        assertEquals(1500, captor.getValue().getOptions().getTimeoutMs());
    }

    @Test
    void shouldRunDefinitionAndUpdateLastRunAt() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record()));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        TaskAcceptedResponse accepted = service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        assertEquals("inst_1", accepted.getTaskId());
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), eq("trace-x"));
        assertNotNull(captor.getValue().getConfigContent());
        verify(definitionRepository).updateLastRunAt(eq(DEF_ID), any());
    }

    @Test
    void shouldRejectRunWhenDisabled() {
        TaskDefinitionRecord disabled = record();
        disabled.setEnabled(false);
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(disabled));

        assertThrows(IllegalArgumentException.class,
                () -> service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x"));
        verify(submissionService, never()).submit(any(), any());
    }

    @Test
    void shouldThrowNotFoundForMissingDefinition() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(DEF_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.run(DEF_ID, null, "trace"));
    }

    @Test
    void shouldPassThroughDatasourceIdAndSetDefinitionIdOnRun() {
        TaskDefinitionRecord recordWithDs = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\",\"datasourceId\":5},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\",\"datasourceId\":5},"
                        + "\"keys\":[\"id\"]}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(recordWithDs));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), eq("trace-x"));
        RunRequest runRequest = captor.getValue();
        assertEquals(DEF_ID, runRequest.getDefinitionId());
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) runRequest.getConfigContent();
        Map<String, Object> source = (Map<String, Object>) config.get("source");
        assertEquals(5, source.get("datasourceId"));
        // 连接注入发生在执行端（RunTaskHandler），提交链路只透传 datasourceId
        assertTrue(source.get("connection") == null
                || ((Map<?, ?>) source.get("connection")).isEmpty());
    }

    @Test
    void shouldApplyPlatformAndPropertiesSavedWithDefinition() {
        TaskDefinitionRecord clusterDefinition = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"keys\":[\"id\"],\"platform\":\"yarn\","
                        + "\"properties\":{\"archive\":\"hdfs:///apps/consilens/runtime.zip\","
                        + "\"queue\":\"analytics\"}}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(clusterDefinition));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), eq("trace-x"));
        assertEquals("yarn", captor.getValue().getOptions().getPlatform());
        assertEquals("hdfs:///apps/consilens/runtime.zip",
                captor.getValue().getOptions().getProperties().get("archive"));
        assertEquals("analytics", captor.getValue().getOptions().getProperties().get("queue"));
    }

    @Test
    void shouldTruncateGeneratedSerialNoForLongNames() {
        TaskDefinitionRecord record = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .name(java.util.UUID.randomUUID().toString().repeat(3))
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), any());
        assertTrue(captor.getValue().getSerialNo().length() <= 128);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEncryptSinkPasswordAtRestAndMaskItOnRead() throws Exception {
        when(definitionRepository.save(any())).thenAnswer(invocation -> {
            TaskDefinitionRecord record = invocation.getArgument(0);
            record.setId(DEF_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });
        TaskDefinitionCreateRequest request = validRequest();
        Map<String, Object> config = new java.util.LinkedHashMap<>(request.getConfig());
        config.put("result", Map.of("sinks", List.of(Map.of(
                "format", "table",
                "type", "result",
                "properties", new java.util.LinkedHashMap<>(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/dq",
                        "username", "sink-user",
                        "password", "sink-secret"))))));
        request.setConfig(config);

        service.create(request);

        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        String stored = captor.getValue().getConfig();
        assertTrue(stored.contains("v2:"), stored);
        assertTrue(!stored.contains("sink-secret"), stored);

        TaskDefinitionRecord record = captor.getValue();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        Map<String, Object> returned = service.get(DEF_ID).getConfig();
        Map<String, Object> result = (Map<String, Object>) returned.get("result");
        List<Map<String, Object>> sinks = (List<Map<String, Object>>) result.get("sinks");
        Map<String, Object> properties = (Map<String, Object>) sinks.get(0).get("properties");
        assertEquals("******", properties.get("password"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRestoreMaskedSinkPasswordOnUpdate() throws Exception {
        com.consilens.server.support.crypto.AesGcmSecretProtector protector =
                com.consilens.server.support.crypto.SecretProtectorTestKeys.protector();
        String encrypted = protector.protect("sink-secret");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> storedConfig = new java.util.LinkedHashMap<>(validRequest().getConfig());
        storedConfig.put("result", Map.of("sinks", List.of(Map.of(
                "format", "table",
                "type", "result",
                "properties", Map.of("url", "jdbc:mysql://127.0.0.1:3306/dq",
                        "username", "sink-user", "password", encrypted)))));
        TaskDefinitionRecord record = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config(mapper.writeValueAsString(storedConfig))
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        when(definitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskDefinitionCreateRequest request = validRequest();
        Map<String, Object> requestConfig = new java.util.LinkedHashMap<>(request.getConfig());
        requestConfig.put("result", Map.of("sinks", List.of(Map.of(
                "format", "table",
                "type", "result",
                "properties", new java.util.LinkedHashMap<>(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/dq",
                        "username", "sink-user", "password", "******"))))));
        request.setConfig(requestConfig);

        service.update(DEF_ID, request);

        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        Map<String, Object> saved = mapper.readValue(captor.getValue().getConfig(), Map.class);
        Map<String, Object> result = (Map<String, Object>) saved.get("result");
        List<Map<String, Object>> sinks = (List<Map<String, Object>>) result.get("sinks");
        Map<String, Object> properties = (Map<String, Object>) sinks.get(0).get("properties");
        assertEquals(encrypted, properties.get("password"));
    }

    private TaskDefinitionCreateRequest validRequest() {
        TaskDefinitionCreateRequest request = new TaskDefinitionCreateRequest();
        request.setName("orders-check");
        request.setDescription("check orders");
        request.setConfig(Map.of(
                "source", cliEndpoint("mysql", "orders", Map.of()),
                "target", cliEndpoint("mysql", "orders", Map.of()),
                "comparison", cliKeys("id")));
        return request;
    }

    private TaskDefinitionRecord record() {
        return TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .enabled(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
