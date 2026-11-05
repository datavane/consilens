package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ServerTopologySnapshot;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    @Test
    void shouldClaimSubmitAndCompleteCommand() throws Exception {
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerTopologyService topologyService = mock(ServerTopologyService.class);
        RunTaskExecuteManager runTaskExecuteManager = mock(RunTaskExecuteManager.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setCommandPollIntervalMs(10);
        properties.getScheduler().setClaimLeaseSeconds(15);

        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(100L)
                .commandKey("cmd-test")
                .taskId(200L)
                .build();
        when(topologyService.snapshot()).thenReturn(ServerTopologySnapshot.builder()
                .totalSlot(4)
                .currentSlot(2)
                .dispatchable(true)
                .build());
        when(topologyService.currentNodeKey()).thenReturn("node-a");
        when(taskCommandRepository.getStartCommand(eq(4), eq(2), any(Instant.class)))
                .thenReturn(command)
                .thenReturn(null);
        when(taskRepository.findById(200L)).thenReturn(Optional.of(TaskInstanceRecord.builder()
                .id(200L)
                .status(TaskStatus.PENDING)
                .build()));
        when(taskCommandRepository.claim(eq(100L), eq("node-a"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(taskRepository.updateClaimed(eq(200L), eq("node-a"), any(Instant.class))).thenReturn(true);

        JobScheduler scheduler = new JobScheduler(taskCommandRepository,
                taskRepository,
                topologyService,
                runTaskExecuteManager,
                runTaskRecoveryService,
                properties);
        try {
            scheduler.startScheduler();

            verify(runTaskExecuteManager, timeout(5000)).addExecuteCommand(command);
            verify(taskRepository, timeout(5000)).updateClaimed(eq(200L), eq("node-a"), any(Instant.class));
            // 命令 DONE 由执行线程在 updateRunning 之后完成，调度器不再提前标记
            verify(taskCommandRepository, never()).markDone(eq(100L), any(Instant.class));
            verify(runTaskRecoveryService, timeout(5000)).recoverCurrentNodeStartupTasks(any(Instant.class));
            verify(runTaskRecoveryService, timeout(5000).atLeastOnce()).recoverExpiredClaims(any(Instant.class));
            verify(runTaskRecoveryService, timeout(5000).atLeastOnce()).recoverTasksOnDeadNodes(any(Instant.class));
        } finally {
            scheduler.stopScheduler();
            scheduler.join(1000);
        }
    }

    @Test
    void shouldReleaseClaimWhenExecuteQueueRejectsCommand() throws Exception {
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerTopologyService topologyService = mock(ServerTopologyService.class);
        RunTaskExecuteManager runTaskExecuteManager = mock(RunTaskExecuteManager.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setCommandPollIntervalMs(10);
        properties.getScheduler().setClaimLeaseSeconds(15);

        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(102L)
                .commandKey("cmd-rejected")
                .taskId(202L)
                .build();
        when(topologyService.snapshot()).thenReturn(ServerTopologySnapshot.builder()
                .totalSlot(4)
                .currentSlot(2)
                .dispatchable(true)
                .build());
        when(topologyService.currentNodeKey()).thenReturn("node-a");
        when(taskCommandRepository.getStartCommand(eq(4), eq(2), any(Instant.class)))
                .thenReturn(command)
                .thenReturn(null);
        when(taskRepository.findById(202L)).thenReturn(Optional.of(TaskInstanceRecord.builder()
                .id(202L)
                .status(TaskStatus.PENDING)
                .build()));
        when(taskCommandRepository.claim(eq(102L), eq("node-a"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(taskRepository.updateClaimed(eq(202L), eq("node-a"), any(Instant.class))).thenReturn(true);
        doThrow(new RejectedExecutionException("queue full"))
                .when(runTaskExecuteManager)
                .addExecuteCommand(command);

        JobScheduler scheduler = new JobScheduler(taskCommandRepository,
                taskRepository,
                topologyService,
                runTaskExecuteManager,
                runTaskRecoveryService,
                properties);
        try {
            scheduler.startScheduler();

            verify(taskCommandRepository, timeout(5000)).resetClaim(eq(102L), any(Instant.class));
            verify(taskRepository, timeout(5000)).releaseClaimed(eq(202L), any(Instant.class));
            verify(taskCommandRepository, never()).markDone(eq(102L), any(Instant.class));
        } finally {
            scheduler.stopScheduler();
            scheduler.join(1000);
        }
    }

    @Test
    void shouldSkipDeadNodeRecoveryWhenCurrentNodeIsNotDispatchable() throws Exception {
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerTopologyService topologyService = mock(ServerTopologyService.class);
        RunTaskExecuteManager runTaskExecuteManager = mock(RunTaskExecuteManager.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setCommandPollIntervalMs(10);
        properties.getScheduler().setClaimLeaseSeconds(15);

        when(topologyService.snapshot()).thenReturn(ServerTopologySnapshot.builder()
                .totalSlot(1)
                .currentSlot(0)
                .dispatchable(false)
                .build());

        JobScheduler scheduler = new JobScheduler(taskCommandRepository,
                taskRepository,
                topologyService,
                runTaskExecuteManager,
                runTaskRecoveryService,
                properties);
        try {
            scheduler.startScheduler();

            // 启动恢复在调度线程的第一轮循环里执行：线程真正被调度起来才算数，
            // 机器负载高时 1s 会等不到（曾在本机全量构建后偶发失败），放到 5s。
            verify(runTaskRecoveryService, timeout(5000).atLeastOnce())
                    .recoverCurrentNodeStartupTasks(any(Instant.class));
            verify(runTaskRecoveryService, timeout(5000).atLeastOnce())
                    .recoverExpiredClaims(any(Instant.class));
            Thread.sleep(50);
            verify(runTaskRecoveryService, never()).recoverTasksOnDeadNodes(any(Instant.class));
            verify(runTaskExecuteManager, never()).addExecuteCommand(any());
        } finally {
            scheduler.stopScheduler();
            scheduler.join(1000);
        }
    }

    @Test
    void shouldRetryStartupRecoveryFailureBeforeDispatching() throws Exception {
        TaskCommandRepository taskCommandRepository = mock(TaskCommandRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ServerTopologyService topologyService = mock(ServerTopologyService.class);
        RunTaskExecuteManager runTaskExecuteManager = mock(RunTaskExecuteManager.class);
        RunTaskRecoveryService runTaskRecoveryService = mock(RunTaskRecoveryService.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getScheduler().setCommandPollIntervalMs(10);
        properties.getScheduler().setClaimLeaseSeconds(15);

        TaskCommandRecord command = TaskCommandRecord.builder()
                .id(101L)
                .commandKey("cmd-after-recovery")
                .taskId(201L)
                .build();
        doThrow(new DataAccessResourceFailureException("temporary startup recovery failure"))
                .doNothing()
                .when(runTaskRecoveryService)
                .recoverCurrentNodeStartupTasks(any(Instant.class));
        when(topologyService.snapshot()).thenReturn(ServerTopologySnapshot.builder()
                .totalSlot(4)
                .currentSlot(2)
                .dispatchable(true)
                .build());
        when(topologyService.currentNodeKey()).thenReturn("node-a");
        when(taskCommandRepository.getStartCommand(eq(4), eq(2), any(Instant.class)))
                .thenReturn(command)
                .thenReturn(null);
        when(taskRepository.findById(201L)).thenReturn(Optional.of(TaskInstanceRecord.builder()
                .id(201L)
                .status(TaskStatus.PENDING)
                .build()));
        when(taskCommandRepository.claim(eq(101L), eq("node-a"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(taskRepository.updateClaimed(eq(201L), eq("node-a"), any(Instant.class))).thenReturn(true);

        JobScheduler scheduler = new JobScheduler(taskCommandRepository,
                taskRepository,
                topologyService,
                runTaskExecuteManager,
                runTaskRecoveryService,
                properties);
        try {
            scheduler.startScheduler();

            verify(runTaskRecoveryService, timeout(10000).times(2))
                    .recoverCurrentNodeStartupTasks(any(Instant.class));
            verify(runTaskExecuteManager, timeout(10000)).addExecuteCommand(command);
        } finally {
            scheduler.stopScheduler();
            scheduler.join(1000);
        }
    }
}
