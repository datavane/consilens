package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.task.impl.RunTaskCommandEnqueueService;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTaskRecoveryServiceTest {

    @Test
    void shouldRequeueTaskAfterExpiredClaimReleased() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(100L)
                .instanceKey("task-requeue")
                .status(TaskStatus.CLAIMED)
                .build();
        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(200L)
                .taskId(100L)
                .build();

        when(taskCommandRepository.listExpiredClaims(now, properties.getScheduler().getRecoveryBatchSize()))
                .thenReturn(List.of(command));
        when(taskCommandRepository.release(200L, now)).thenReturn(true);
        when(taskRepository.releaseClaimed(100L, now)).thenReturn(true);
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverExpiredClaims(now);

        verify(taskCommandRepository).release(200L, now);
        verify(taskRepository).releaseClaimed(100L, now);
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
    }

    @Test
    void shouldResetAndRequeueRetryableRunningTask() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(101L)
                .instanceKey("task-retry")
                .status(TaskStatus.RUNNING)
                .retryCount(0)
                .maxRetryCount(3)
                .build();

        when(taskRepository.updateRetryableFromStatuses(eq(101L), any(), eq("TEMPORARY_ERROR"), eq("temporary"), eq(now)))
                .thenReturn(true);
        when(taskRepository.resetForRetry(eq(101L), any(), eq(now))).thenReturn(true);

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.retryOrFail(task, "TEMPORARY_ERROR", "temporary", now);

        verify(taskCommandRepository).releaseClaimedByTaskId(101L, now);
        verify(taskRepository).resetForRetry(eq(101L), any(), eq(now));
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
    }

    @Test
    void shouldRecoverDeadNodeTasksEvenWhenNoAliveNodesAreReported() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(102L)
                .instanceKey("task-dead-node")
                .status(TaskStatus.RUNNING)
                .retryCount(0)
                .maxRetryCount(3)
                .build();

        when(serverNodeQueryService.listAliveNodes()).thenReturn(List.of());
        when(taskRepository.listByStatusesExcludingExecuteNodes(any(), any(), eq(properties.getScheduler().getRecoveryBatchSize())))
                .thenReturn(List.of(task));
        when(taskRepository.updateRetryableFromStatuses(eq(102L), any(), eq("EXECUTE_NODE_LOST"), eq("Execution node heartbeat expired"), eq(now)))
                .thenReturn(true);
        when(taskRepository.resetForRetry(eq(102L), any(), eq(now))).thenReturn(true);

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverTasksOnDeadNodes(now);

        verify(taskCommandRepository).releaseClaimedByTaskId(102L, now);
        verify(taskRepository).resetForRetry(eq(102L), any(), eq(now));
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
        verify(taskRepository, never()).listByStatusesExcludingExecuteNodes(any(), any(), eq(0));
    }

    @Test
    void shouldRequeueRunningTaskWhenHeartbeatStopped() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(103L)
                .instanceKey("task-stale-running")
                .status(TaskStatus.RUNNING)
                .retryCount(0)
                .maxRetryCount(3)
                .build();
        Instant staleBefore = now.minusSeconds(properties.getScheduler().getClaimLeaseSeconds() * 3L);

        when(taskRepository.listStaleRunning(staleBefore, properties.getScheduler().getRecoveryBatchSize()))
                .thenReturn(List.of(task));
        when(taskRepository.updateRetryableFromStatuses(eq(103L), any(),
                eq("EXECUTE_HEARTBEAT_LOST"), eq("Execution heartbeat expired"), eq(now)))
                .thenReturn(true);
        when(taskRepository.resetForRetry(eq(103L), any(), eq(now))).thenReturn(true);

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverStaleRunningTasks(now);

        verify(taskCommandRepository).releaseClaimedByTaskId(103L, now);
        verify(taskRepository).resetForRetry(eq(103L), any(), eq(now));
        verify(runTaskCommandEnqueueService).enqueue(eq(task), eq(now));
    }

    @Test
    void shouldNotConfirmCancellationOnlyBecauseExecutionNodeIsUnavailable() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        when(serverNodeQueryService.listAliveNodes()).thenReturn(List.of());
        when(taskRepository.listByStatusesExcludingExecuteNodes(
                eq(List.of(TaskStatus.CLAIMED, TaskStatus.RUNNING)),
                any(),
                eq(properties.getScheduler().getRecoveryBatchSize())))
                .thenReturn(List.of());

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverTasksOnDeadNodes(now);

        verify(taskRepository, never()).confirmCancellation(anyLong(), any());
    }

    @Test
    void shouldDrainAllClaimedCommandsDuringStartupRecovery() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        RunTaskCommandEnqueueService runTaskCommandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        ServerTopologyService serverTopologyService = mock(ServerTopologyService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        Instant now = Instant.now();
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(104L)
                .instanceKey("task-startup-drain")
                .status(TaskStatus.CLAIMED)
                .build();
        TaskCommandRecord first = TaskCommandRecord.builder().id(300L).taskId(104L).build();
        TaskCommandRecord second = TaskCommandRecord.builder().id(301L).taskId(104L).build();

        when(serverTopologyService.currentNodeKey()).thenReturn("node-a");
        when(taskCommandRepository.listClaimedByExecuteNode("node-a",
                properties.getScheduler().getRecoveryBatchSize()))
                .thenReturn(List.of(first), List.of(second), List.of());
        when(taskCommandRepository.release(anyLong(), eq(now))).thenReturn(true);
        when(taskRepository.releaseClaimed(anyLong(), eq(now))).thenReturn(true);
        when(taskRepository.findById(104L)).thenReturn(Optional.of(task));

        RunTaskRecoveryService recoveryService = new RunTaskRecoveryService(taskRepository,
                taskCommandRepository,
                runTaskCommandEnqueueService,
                serverNodeQueryService,
                serverTopologyService,
                properties);

        recoveryService.recoverCurrentNodeStartupTasks(now);

        verify(taskCommandRepository, times(3)).listClaimedByExecuteNode("node-a",
                properties.getScheduler().getRecoveryBatchSize());
        verify(taskCommandRepository).release(300L, now);
        verify(taskCommandRepository).release(301L, now);
    }
}
