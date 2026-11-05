package com.consilens.server.application.task;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.application.task.impl.RunTaskCommandEnqueueService;
import com.consilens.server.application.task.impl.RunTaskSubmissionServiceImpl;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.hash.Sha256Support;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTaskSubmissionServiceImplTest {

    @Test
    void shouldReturnExistingTaskWhenConcurrentInsertHitsSerialNoUniqueKey() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        RunTaskCommandEnqueueService commandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-concurrent");
        request.setConfigArtifactId("artifact-config");
        String requestPayload = objectMapper.writeValueAsString(request);
        String requestHash = Sha256Support.hex(requestPayload);
        TaskInstanceRecord existing = TaskInstanceRecord.builder()
                .id(10L)
                .instanceKey("task-existing")
                .serialNo(request.getSerialNo())
                .requestHash(requestHash)
                .status(TaskStatus.PENDING)
                .build();
        when(taskRepository.lockBySerialNo(request.getSerialNo())).thenReturn(Optional.empty());
        when(taskRepository.save(any())).thenThrow(new DuplicateKeyException("serial_no"));
        when(taskRepository.findBySerialNo(request.getSerialNo())).thenReturn(Optional.of(existing));

        RunTaskSubmissionServiceImpl service = new RunTaskSubmissionServiceImpl(taskRepository,
                commandEnqueueService,
                new ConsilensServerProperties(),
                objectMapper,
                new TransactionTemplate(transactionManager()),
                com.consilens.server.support.crypto.SecretProtectorTestKeys.protector());

        TaskAcceptedResponse response = service.submit(request, "trace-test");

        assertThat(response.getTaskId()).isEqualTo("task-existing");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(commandEnqueueService, never()).enqueue(any(), any());
    }

    @Test
    void shouldEncryptSinkPasswordInPersistedRequestPayload() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        RunTaskCommandEnqueueService commandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-sink");
        request.setConfigContent(new java.util.LinkedHashMap<>(java.util.Map.of(
                "source", java.util.Map.of("type", "mysql", "table", "s"),
                "target", java.util.Map.of("type", "mysql", "table", "t"),
                "keys", java.util.List.of("id"),
                "result", java.util.Map.of("sinks", java.util.List.of(java.util.Map.of(
                        "format", "table",
                        "type", "result",
                        "properties", new java.util.LinkedHashMap<>(java.util.Map.of(
                                "url", "jdbc:mysql://127.0.0.1:3306/dq",
                                "password", "sink-secret"))))))));
        when(taskRepository.lockBySerialNo(request.getSerialNo())).thenReturn(Optional.empty());
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RunTaskSubmissionServiceImpl service = new RunTaskSubmissionServiceImpl(taskRepository,
                commandEnqueueService,
                new ConsilensServerProperties(),
                objectMapper,
                new TransactionTemplate(transactionManager()),
                com.consilens.server.support.crypto.SecretProtectorTestKeys.protector());

        service.submit(request, "trace-sink");

        org.mockito.ArgumentCaptor<TaskInstanceRecord> captor =
                org.mockito.ArgumentCaptor.forClass(TaskInstanceRecord.class);
        verify(taskRepository).save(captor.capture());
        String payload = captor.getValue().getRequestPayload();
        assertThat(payload).contains("v2:").doesNotContain("sink-secret");
    }

    @Test
    void shouldRejectSensitiveConnectionPropertiesBeforePersistingPayload() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        RunTaskCommandEnqueueService commandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-props");
        java.util.Map<String, Object> connection = new java.util.LinkedHashMap<>();
        connection.put("url", "jdbc:mysql://127.0.0.1:3306/db");
        connection.put("properties", "useSSL=false&password=plaintext-secret");
        request.setConfigContent(new java.util.LinkedHashMap<>(java.util.Map.of(
                "source", java.util.Map.of("type", "mysql", "table", "s", "connection", connection),
                "target", java.util.Map.of("type", "mysql", "table", "t",
                        "connection", new java.util.LinkedHashMap<>(connection)),
                "keys", java.util.List.of("id"))));

        RunTaskSubmissionServiceImpl service = new RunTaskSubmissionServiceImpl(taskRepository,
                commandEnqueueService,
                new ConsilensServerProperties(),
                objectMapper,
                new TransactionTemplate(transactionManager()),
                com.consilens.server.support.crypto.SecretProtectorTestKeys.protector());

        assertThatThrownBy(() -> service.submit(request, "trace-props"))
                .isInstanceOf(InvalidInputException.class);
        verify(taskRepository, never()).save(any());
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }
}
