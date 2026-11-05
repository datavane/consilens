package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.CapabilityExecutionException;
import com.consilens.server.application.capability.ServerCapabilityFacade;
import com.consilens.server.application.capability.TaskCancellationException;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RunTaskExecuteManagerTest {

    @Test
    void shouldExecuteRunTaskAndUpdateSuccess() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        RunRequest request = new RunRequest();
        request.setSerialNo("serial-test");
        request.setConfigArtifactId("artifact-config");
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(10L)
                .instanceKey("task-test")
                .traceId("trace-test")
                .requestPayload(objectMapper.writeValueAsString(request))
                .build();
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(10L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenReturn(ArtifactRefDto.builder()
                .id("artifact-result")
                .type("RUN_RESULT")
                .format("json")
                .build());

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                objectMapper,
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false),
                taskCommandRepository);
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(20L)
                    .taskId(10L)
                    .commandKey("cmd-test")
                    .build());

            verify(taskRepository, timeout(1000)).updateRunning(eq(10L), eq("node-a"), any(Instant.class));
            verify(taskRepository, timeout(1000)).updateSuccess(eq(10L), eq("artifact-result"), any(Instant.class));
            // 任务进入 RUNNING 后命令才可标 DONE
            verify(taskCommandRepository, timeout(1000)).markDone(eq(20L), any(Instant.class));
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldRecoverRetryableExecutionFailure() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        RunRequest request = new RunRequest();
        request.setSerialNo("serial-retry");
        request.setConfigArtifactId("artifact-config");
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(11L)
                .instanceKey("task-retry")
                .traceId("trace-retry")
                .requestPayload(objectMapper.writeValueAsString(request))
                .retryCount(0)
                .maxRetryCount(3)
                .build();
        when(taskRepository.findById(11L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(11L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenThrow(
                new CapabilityExecutionException("TEMPORARY_ERROR",
                        "temporary failure",
                        null,
                        true,
                        null));

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                objectMapper,
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false));
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(21L)
                    .taskId(11L)
                    .commandKey("cmd-retry")
                    .build());

            verify(runTaskRecoveryService, timeout(1000))
                    .retryOrFail(eq(task), eq("TEMPORARY_ERROR"), eq("temporary failure"), any(Instant.class));
            verify(taskRepository, never()).updateFailure(eq(11L), any(), any(), any());
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldCancelHeartbeatWhenRequestPayloadCannotBeParsed() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setClaimLeaseSeconds(1);
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(12L)
                .instanceKey("task-invalid-payload")
                .requestPayload("not-json")
                .build();
        when(taskRepository.findById(12L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(12L), eq("node-a"), any(Instant.class))).thenReturn(true);

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                new ObjectMapper(),
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false));
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(22L)
                    .taskId(12L)
                    .commandKey("cmd-invalid-payload")
                    .build());

            verify(runTaskRecoveryService, timeout(1000))
                    .retryOrFail(eq(task), eq("RUN_DISPATCH_ERROR"), any(), any(Instant.class));
            verify(taskRepository, after(1200).never()).renewRunning(eq(12L), any(Instant.class));
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldConfirmCancellationInsteadOfRetryingWhenExecutionStopsAfterCancelRequest() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-cancel");
        request.setConfigArtifactId("artifact-config");
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(13L)
                .instanceKey("task-cancel")
                .traceId("trace-cancel")
                .requestPayload(objectMapper.writeValueAsString(request))
                .build();
        when(taskRepository.findById(13L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(13L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenThrow(new TaskCancellationException("task-cancel"));
        when(taskRepository.confirmCancellation(eq(13L), any(Instant.class))).thenReturn(true);

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                new ConsilensServerProperties(),
                objectMapper,
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false));
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(23L)
                    .taskId(13L)
                    .commandKey("cmd-cancel")
                    .build());

            verify(taskRepository, timeout(1000)).confirmCancellation(eq(13L), any(Instant.class));
            verify(runTaskRecoveryService, never()).retryOrFail(any(), any(), any(), any());
            verify(taskRepository, never()).updateSuccess(eq(13L), any(), any());
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldConfirmCancellationWhenItArrivesDuringFailureHandling() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-cancel-race");
        request.setConfigArtifactId("artifact-config");
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(14L)
                .instanceKey("task-cancel-race")
                .traceId("trace-cancel-race")
                .requestPayload(objectMapper.writeValueAsString(request))
                .build();
        when(taskRepository.findById(14L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(14L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenThrow(
                new CapabilityExecutionException("TEMPORARY_ERROR", "temporary failure", null, true, null));
        when(taskRepository.confirmCancellation(eq(14L), any(Instant.class))).thenReturn(false, true);

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                new ConsilensServerProperties(),
                objectMapper,
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false));
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(24L)
                    .taskId(14L)
                    .commandKey("cmd-cancel-race")
                    .build());

            verify(runTaskRecoveryService, timeout(1000))
                    .retryOrFail(eq(task), eq("TEMPORARY_ERROR"), eq("temporary failure"), any(Instant.class));
            verify(taskRepository, timeout(1000).times(2)).confirmCancellation(eq(14L), any(Instant.class));
        } finally {
            manager.stop();
        }
    }

    @Test
    void shouldKeepHeartbeatAliveAfterTransientRenewFailure() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerCapabilityFacade serverCapabilityFacade = mock(ServerCapabilityFacade.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setClaimLeaseSeconds(3);
        ObjectMapper objectMapper = new ObjectMapper();

        RunRequest request = new RunRequest();
        request.setSerialNo("serial-heartbeat");
        request.setConfigArtifactId("artifact-config");
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(15L)
                .instanceKey("task-heartbeat")
                .traceId("trace-heartbeat")
                .requestPayload(objectMapper.writeValueAsString(request))
                .build();
        when(taskRepository.findById(15L)).thenReturn(Optional.of(task));
        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskRepository.updateRunning(eq(15L), eq("node-a"), any(Instant.class))).thenReturn(true);
        when(taskRepository.updateSuccess(eq(15L), any(), any(Instant.class))).thenReturn(true);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger renewCalls = new AtomicInteger();
        when(taskRepository.renewRunning(eq(15L), any(Instant.class))).thenAnswer(invocation -> {
            // 第一次心跳模拟 DB 抖动，之后恢复；固定间隔任务不能因此被取消。
            if (renewCalls.incrementAndGet() == 1) {
                throw new RuntimeException("transient db error");
            }
            return true;
        });
        when(serverCapabilityFacade.run(any(RunRequest.class), any())).thenAnswer(invocation -> {
            release.await(8, TimeUnit.SECONDS);
            return ArtifactRefDto.builder()
                    .id("artifact-heartbeat")
                    .type("RUN_RESULT")
                    .format("json")
                    .build();
        });

        RunTaskExecuteManager manager = new RunTaskExecuteManager(taskRepository,
                serverCapabilityFacade,
                serverTopologyService,
                runTaskRecoveryService,
                properties,
                objectMapper,
                new com.consilens.server.infrastructure.log.RunLogStore(
                        java.nio.file.Paths.get("target/test-run-logs"), false));
        try {
            manager.addExecuteCommand(TaskCommandRecord.builder()
                    .id(25L)
                    .taskId(15L)
                    .commandKey("cmd-heartbeat")
                    .build());

            verify(taskRepository, timeout(6000).atLeast(2))
                    .renewRunning(eq(15L), any(Instant.class));
        } finally {
            release.countDown();
            manager.stop();
        }
    }
}
