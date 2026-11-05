package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.task.impl.RunTaskCommandEnqueueService;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "consilens.server.scheduler", name = "enabled", havingValue = "true")
public class RunTaskRecoveryService {

    private static final List<TaskStatus> RECOVERABLE_STATUSES = List.of(TaskStatus.CLAIMED, TaskStatus.RUNNING);
    private static final List<TaskStatus> CANCELLATION_STATUSES = List.of(TaskStatus.CANCEL_REQUESTED);

    /** 启动恢复最多连续清空 10 个批次，避免异常数据导致无限循环。 */
    private static final int MAX_STARTUP_RECOVERY_ROUNDS = 10;

    /**
     * 连续错过 3 个 claim 租约周期（心跳按调度周期刷新）才判定为 stale：
     * 1 个周期过于敏感，过大则延迟故障转移。
     */
    private static final long STALE_LEASE_MULTIPLIER = 3L;

    private final TaskRepository taskRepository;
    private final TaskCommandRepository taskCommandRepository;
    private final RunTaskCommandEnqueueService runTaskCommandEnqueueService;
    private final ServerNodeQueryService serverNodeQueryService;
    private final ServerTopologyService serverTopologyService;
    private final ConsilensServerProperties properties;

    public RunTaskRecoveryService(TaskRepository taskRepository,
                                  TaskCommandRepository taskCommandRepository,
                                  RunTaskCommandEnqueueService runTaskCommandEnqueueService,
                                  ServerNodeQueryService serverNodeQueryService,
                                  ServerTopologyService serverTopologyService,
                                  ConsilensServerProperties properties) {
        this.taskRepository = taskRepository;
        this.taskCommandRepository = taskCommandRepository;
        this.runTaskCommandEnqueueService = runTaskCommandEnqueueService;
        this.serverNodeQueryService = serverNodeQueryService;
        this.serverTopologyService = serverTopologyService;
        this.properties = properties;
    }

    @Transactional
    public void recoverCurrentNodeStartupTasks(Instant now) {
        String currentNodeKey = serverTopologyService.currentNodeKey();
        int limit = properties.getScheduler().getRecoveryBatchSize();
        // Keep draining until this batch is empty so more than recoveryBatchSize
        // commands on a busy node are not left waiting for lease expiry.
        int rounds = 0;
        while (rounds++ < MAX_STARTUP_RECOVERY_ROUNDS) {
            List<TaskCommandRecord> claimed = taskCommandRepository.listClaimedByExecuteNode(currentNodeKey, limit);
            if (claimed.isEmpty()) {
                break;
            }
            claimed.forEach(command -> releaseCommandAndClaimedTask(command, now));
        }
        taskRepository.listByExecuteNodeAndStatuses(currentNodeKey, RECOVERABLE_STATUSES, limit)
                .forEach(task -> retryOrFail(task,
                        "NODE_RESTARTED",
                        "Execution node restarted before task completed",
                        now));
        // 本节点重启前已收到取消请求：执行线程已不存在，直接确认取消，避免永久卡在 CANCEL_REQUESTED
        taskRepository.listByExecuteNodeAndStatuses(currentNodeKey, CANCELLATION_STATUSES, limit)
                .forEach(task -> confirmCancellation(task, now));
    }

    @Transactional
    public void recoverExpiredClaims(Instant now) {
        taskCommandRepository.listExpiredClaims(now, properties.getScheduler().getRecoveryBatchSize())
                .forEach(command -> releaseCommandAndClaimedTask(command, now));
    }

    @Transactional
    public void recoverStaleRunningTasks(Instant now) {
        long staleAfterSeconds =
                properties.getScheduler().getClaimLeaseSeconds() * STALE_LEASE_MULTIPLIER;
        taskRepository.listStaleRunning(now.minusSeconds(staleAfterSeconds),
                        properties.getScheduler().getRecoveryBatchSize())
                .forEach(task -> retryOrFail(task, "EXECUTE_HEARTBEAT_LOST", "Execution heartbeat expired", now));
        // 取消请求发出后执行线程失联：不会再有人调用 confirmCancellation
        taskRepository.listStaleByStatus(TaskStatus.CANCEL_REQUESTED,
                        now.minusSeconds(staleAfterSeconds),
                        properties.getScheduler().getRecoveryBatchSize())
                .forEach(task -> confirmCancellation(task, now));
    }

    @Transactional
    public void recoverTasksOnDeadNodes(Instant now) {
        Set<String> aliveNodeKeys = serverNodeQueryService.listAliveNodes().stream()
                .map(ServerNodeRecord::getNodeKey)
                .collect(Collectors.toSet());
        taskRepository.listByStatusesExcludingExecuteNodes(RECOVERABLE_STATUSES,
                        aliveNodeKeys,
                        properties.getScheduler().getRecoveryBatchSize())
                .forEach(task -> retryOrFail(task,
                        "EXECUTE_NODE_LOST",
                        "Execution node heartbeat expired",
                        now));
        taskRepository.listByStatusesExcludingExecuteNodes(CANCELLATION_STATUSES,
                        aliveNodeKeys,
                        properties.getScheduler().getRecoveryBatchSize())
                .forEach(task -> confirmCancellation(task, now));
    }

    private void confirmCancellation(TaskInstanceRecord task, Instant now) {
        if (task != null && taskRepository.confirmCancellation(task.getId(), now)) {
            log.info("Confirmed cancellation for run task {} after execution node/lease loss",
                    task.getInstanceKey());
        }
    }

    @Transactional
    public void retryOrFail(TaskInstanceRecord task, String errorCode, String errorMessage, Instant now) {
        if (task == null) {
            return;
        }
        taskCommandRepository.releaseClaimedByTaskId(task.getId(), now);
        if (hasRetryBudget(task)) {
            boolean updated = taskRepository.updateRetryableFromStatuses(task.getId(),
                    RECOVERABLE_STATUSES,
                    errorCode,
                    errorMessage,
                    now);
            if (!updated) {
                return;
            }
            String traceId = "trace_" + UUID.randomUUID();
            if (taskRepository.resetForRetry(task.getId(), traceId, now)) {
                task.setTraceId(traceId);
                runTaskCommandEnqueueService.enqueue(task, now);
                log.info("Recovered run task {} for retry", task.getInstanceKey());
            }
        } else if (taskRepository.updateFailureFromStatuses(task.getId(),
                RECOVERABLE_STATUSES,
                errorCode,
                errorMessage,
                now)) {
            log.warn("Failed run task {} after retry budget exhausted", task.getInstanceKey());
        }
    }

    private void releaseCommandAndClaimedTask(TaskCommandRecord command, Instant now) {
        if (taskCommandRepository.release(command.getId(), now)) {
            requeueReleasedClaimedTask(command.getTaskId(), now);
        }
    }

    private void requeueReleasedClaimedTask(Long taskId, Instant now) {
        if (taskRepository.releaseClaimed(taskId, now)) {
            taskRepository.findById(taskId).ifPresent(task -> runTaskCommandEnqueueService.enqueue(task, now));
        }
    }

    private boolean hasRetryBudget(TaskInstanceRecord task) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetryCount = task.getMaxRetryCount() == null
                ? properties.getScheduler().getMaxRetryCount()
                : task.getMaxRetryCount();
        return retryCount < maxRetryCount;
    }
}
