package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.CapabilityExecutionException;
import com.consilens.server.application.capability.ServerCapabilityFacade;
import com.consilens.server.application.capability.TaskCancellationException;
import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.log.RunLogStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "consilens.server.scheduler", name = "enabled", havingValue = "true")
public class RunTaskExecuteManager {

    private final TaskRepository taskRepository;
    private final TaskCommandRepository taskCommandRepository;
    private final ServerCapabilityFacade serverCapabilityFacade;
    private final ServerTopologyService serverTopologyService;
    private final RunTaskRecoveryService runTaskRecoveryService;
    private final ObjectMapper objectMapper;
    private final ConsilensServerProperties properties;
    private final RunLogStore runLogStore;
    private final ThreadPoolExecutor executorService;
    private final ScheduledExecutorService heartbeatScheduler;

    /** 测试/旧调用入口：未注入命令仓库，不更新命令状态。 */
    public RunTaskExecuteManager(TaskRepository taskRepository,
                                 ServerCapabilityFacade serverCapabilityFacade,
                                 ServerTopologyService serverTopologyService,
                                 RunTaskRecoveryService runTaskRecoveryService,
                                 ConsilensServerProperties properties,
                                 ObjectMapper objectMapper,
                                 RunLogStore runLogStore) {
        this(taskRepository, serverCapabilityFacade, serverTopologyService, runTaskRecoveryService,
                properties, objectMapper, runLogStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunTaskExecuteManager(TaskRepository taskRepository,
                                 ServerCapabilityFacade serverCapabilityFacade,
                                 ServerTopologyService serverTopologyService,
                                 RunTaskRecoveryService runTaskRecoveryService,
                                 ConsilensServerProperties properties,
                                 ObjectMapper objectMapper,
                                 RunLogStore runLogStore,
                                 TaskCommandRepository taskCommandRepository) {
        this.taskRepository = taskRepository;
        this.taskCommandRepository = taskCommandRepository;
        this.serverCapabilityFacade = serverCapabilityFacade;
        this.serverTopologyService = serverTopologyService;
        this.runTaskRecoveryService = runTaskRecoveryService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runLogStore = runLogStore;
        int executeThreads = Math.max(properties.getScheduler().getExecuteThreads(), 1);
        int queueCapacity = Math.max(properties.getScheduler().getExecuteQueueCapacity(), 1);
        this.executorService = new ThreadPoolExecutor(executeThreads,
                executeThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new SchedulerThreadFactory("run-task-execute-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                new SchedulerThreadFactory("run-task-heartbeat-"));
    }

    @PostConstruct
    public void start() {
        log.info("run task execute manager started");
    }

    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
        heartbeatScheduler.shutdownNow();
    }

    public void addExecuteCommand(TaskCommandRecord command) {
        executorService.submit(() -> dispatch(command));
    }

    private void dispatch(TaskCommandRecord command) {
        TaskInstanceRecord task = taskRepository.findById(command.getTaskId()).orElse(null);
        if (task == null) {
            markCommandDone(command);
            return;
        }

        // 运行实例日志的 MDC 生命周期在执行入口建立（而不是 handler 内部）：
        // 覆盖 dispatch → handler → 失败记录 → 落库 的完整链路，失败堆栈也在 MDC 有效期内记录
        runLogStore.beginRun(task.getInstanceKey());
        log.info("run dispatched: instanceKey={}, taskId={}", task.getInstanceKey(), task.getId());
        Instant startTime = Instant.now();
        ScheduledFuture<?> heartbeat = null;
        try {
            boolean running = taskRepository.updateRunning(task.getId(),
                    serverTopologyService.currentNodeKey(), startTime);
            // 命令必须在任务真正进入 RUNNING 之后才标 DONE：反过来会留下
            // “命令已消费、任务仍 PENDING”的不可恢复窗口（进程在两步之间退出时尤其明显）。
            markCommandDone(command);
            if (!running) {
                return;
            }
            heartbeat = startHeartbeat(task);
            RunRequest runRequest = objectMapper.readValue(task.getRequestPayload(), RunRequest.class);
            ArtifactRefDto artifact = serverCapabilityFacade.run(runRequest, TaskExecutionContext.builder()
                    .instanceKey(task.getInstanceKey())
                    .taskId(task.getId())
                    .traceId(task.getTraceId())
                    .nodeKey(serverTopologyService.currentNodeKey())
                    .startTime(startTime)
                    .build());
            if (!taskRepository.updateSuccess(task.getId(), artifact.getId(), Instant.now())) {
                confirmCancellation(task);
            }
        } catch (TaskCancellationException exception) {
            log.info("Run task {} cancelled: {}", task.getInstanceKey(), exception.getMessage());
            confirmCancellation(task);
        } catch (CapabilityExecutionException exception) {
            // 失败原因与堆栈写入实例日志，便于在详情页直接定位
            log.error("Run task {} failed: {} - {}",
                    task.getInstanceKey(), exception.getErrorCode(), exception.getMessage(), exception);
            if (confirmCancellation(task)) {
                return;
            }
            if (exception.isRetryable()) {
                runTaskRecoveryService.retryOrFail(task,
                        exception.getErrorCode(),
                        exception.getMessage(),
                        Instant.now());
            } else {
                if (!taskRepository.updateFailure(task.getId(),
                        exception.getErrorCode(),
                        exception.getMessage(),
                        Instant.now())) {
                    confirmCancellation(task);
                }
            }
            confirmCancellation(task);
        } catch (Exception exception) {
            if (confirmCancellation(task)) {
                return;
            }
            log.error("Run task {} failed to dispatch: {}", task.getInstanceKey(), exception.getMessage(), exception);
            runTaskRecoveryService.retryOrFail(task, "RUN_DISPATCH_ERROR", exception.getMessage(), Instant.now());
            confirmCancellation(task);
        } catch (LinkageError error) {
            // 缺少运行时依赖（如集群客户端未打包）抛的是 Error 而非 Exception：
            // 不捕获会让执行线程直接死亡、任务靠心跳超时才被回收，现场表现为长时间 RUNNING。
            // 缺依赖重试也不会自愈，直接判定失败。
            log.error("Run task {} failed to dispatch due to missing runtime dependency",
                    task.getInstanceKey(), error);
            if (!taskRepository.updateFailure(task.getId(), "CLUSTER_CLIENT_UNAVAILABLE",
                    String.valueOf(error), Instant.now())) {
                confirmCancellation(task);
            }
            confirmCancellation(task);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            runLogStore.endRun(task.getInstanceKey());
        }
    }

    /** 标记命令已消费；失败时保留 CLAIMED 让租约恢复链路兜底，不能影响任务执行。 */
    private void markCommandDone(TaskCommandRecord command) {
        if (taskCommandRepository == null || command == null || command.getId() == null) {
            return;
        }
        try {
            taskCommandRepository.markDone(command.getId(), Instant.now());
        } catch (RuntimeException exception) {
            log.warn("failed to mark run command done: commandId={}, taskId={}",
                    command.getId(), command.getTaskId(), exception);
        }
    }

    private boolean confirmCancellation(TaskInstanceRecord task) {
        if (taskRepository.confirmCancellation(task.getId(), Instant.now())) {
            log.info("Run task {} cancelled after execution stopped", task.getInstanceKey());
            return true;
        }
        return false;
    }

    /**
     * Renew the task update time periodically so the recovery guard treats a
     * long-running comparison as alive and only reclaims tasks whose heartbeat
     * actually stopped.
     */
    private ScheduledFuture<?> startHeartbeat(TaskInstanceRecord task) {
        long leaseSeconds = Math.max(properties.getScheduler().getClaimLeaseSeconds(), 1L);
        long heartbeatIntervalSeconds = Math.max(leaseSeconds / 3, 1L);
        return heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        taskRepository.renewRunning(task.getId(), Instant.now());
                    } catch (RuntimeException exception) {
                        // scheduleAtFixedRate 在异常后会永久停掉后续执行；单次 DB 抖动
                        // 不能让长任务失去心跳，否则会被恢复扫描当作失联并重复执行。
                        log.warn("run task heartbeat failed: taskId={}, instanceKey={}",
                                task.getId(), task.getInstanceKey(), exception);
                    }
                },
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);
    }
}
