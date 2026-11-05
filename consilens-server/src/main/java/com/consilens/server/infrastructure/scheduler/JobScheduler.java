package com.consilens.server.infrastructure.scheduler;

import com.consilens.server.application.topology.ServerTopologyService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ServerTopologySnapshot;
import com.consilens.server.domain.model.TaskCommandRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskCommandRepository;
import com.consilens.server.domain.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "consilens.server.scheduler", name = "enabled", havingValue = "true")
public class JobScheduler extends Thread {

    private static final long DEFAULT_SLEEP_TIME_MILLIS = 1000L;

    private final ExponentialBackoffRetryStrategy retryStrategy;

    private final TaskCommandRepository taskCommandRepository;
    private final TaskRepository taskRepository;
    private final ServerTopologyService topologyService;
    private final RunTaskExecuteManager runTaskExecuteManager;
    private final RunTaskRecoveryService runTaskRecoveryService;
    private final ConsilensServerProperties properties;

    private volatile boolean running = true;

    public JobScheduler(TaskCommandRepository taskCommandRepository,
                        TaskRepository taskRepository,
                        ServerTopologyService topologyService,
                        RunTaskExecuteManager runTaskExecuteManager,
                        RunTaskRecoveryService runTaskRecoveryService,
                        ConsilensServerProperties properties) {
        super("run-task-job-scheduler");
        this.taskCommandRepository = taskCommandRepository;
        this.taskRepository = taskRepository;
        this.topologyService = topologyService;
        this.runTaskExecuteManager = runTaskExecuteManager;
        this.runTaskRecoveryService = runTaskRecoveryService;
        this.properties = properties;
        this.retryStrategy = new ExponentialBackoffRetryStrategy(
                properties.getScheduler().getMaxRetryCount());
    }

    @PostConstruct
    public void startScheduler() {
        start();
    }

    @PreDestroy
    public void stopScheduler() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        log.info("job scheduler started");

        int retryNum = 0;
        boolean startupRecovered = false;
        while (running) {
            TaskCommandRecord command = null;
            boolean commandSubmitted = false;
            try {
                Instant now = Instant.now();
                if (!startupRecovered) {
                    runTaskRecoveryService.recoverCurrentNodeStartupTasks(now);
                    startupRecovered = true;
                }
                runTaskRecoveryService.recoverExpiredClaims(now);
                runTaskRecoveryService.recoverStaleRunningTasks(now);

                ServerTopologySnapshot snapshot = topologyService.snapshot();
                if (!snapshot.isDispatchable()) {
                    sleepMillis(DEFAULT_SLEEP_TIME_MILLIS * 4);
                    continue;
                }

                runTaskRecoveryService.recoverTasksOnDeadNodes(now);

                command = taskCommandRepository.getStartCommand(snapshot.getTotalSlot(),
                        snapshot.getCurrentSlot(),
                        now);
                if (command != null) {
                    TaskCommandRecord claimedCommand = claim(command, now);
                    if (claimedCommand != null) {
                        command = claimedCommand;
                        log.info("start submit run task command : {}", claimedCommand.getCommandKey());
                        runTaskExecuteManager.addExecuteCommand(claimedCommand);
                        commandSubmitted = true;
                        log.info("submit success, run task command : {}", claimedCommand.getCommandKey());
                        // 命令由执行线程在任务置为 RUNNING 后标 DONE；这里提前标记
                        // 会在两步之间进程退出时留下永久 PENDING 的任务。
                    }
                    sleepMillis(properties.getScheduler().getCommandPollIntervalMs());
                } else {
                    sleepMillis(properties.getScheduler().getCommandPollIntervalMs() * 4L);
                }

                retryNum = retryStrategy.reset();
            } catch (DataAccessException exception) {
                releaseUnsubmittedCommand(command, commandSubmitted);
                retryNum = backoff("Database error while scheduling run task", retryNum, exception);
            } catch (Exception exception) {
                releaseUnsubmittedCommand(command, commandSubmitted);
                log.error("Unexpected error while scheduling run task command: {}", command, exception);
                retryNum = backoff("Unexpected error while scheduling run task", retryNum, exception);
            }
        }
    }

    private TaskCommandRecord claim(TaskCommandRecord command, Instant now) {
        String executeNodeKey = topologyService.currentNodeKey();
        Optional<TaskInstanceRecord> task = taskRepository.findById(command.getTaskId());
        if (task.isEmpty() || task.get().getStatus() != TaskStatus.PENDING) {
            taskCommandRepository.releaseOpenByTaskId(command.getTaskId(), now);
            return null;
        }
        boolean claimed = taskCommandRepository.claim(command.getId(),
                executeNodeKey,
                now,
                now.plusSeconds(properties.getScheduler().getClaimLeaseSeconds()));
        if (!claimed) {
            return null;
        }
        if (!taskRepository.updateClaimed(command.getTaskId(), executeNodeKey, now)) {
            taskCommandRepository.release(command.getId(), Instant.now());
            return null;
        }
        command.setExecuteNodeKey(executeNodeKey);
        command.setLockTime(now);
        command.setLockUntil(now.plusSeconds(properties.getScheduler().getClaimLeaseSeconds()));
        return command;
    }

    private void releaseUnsubmittedCommand(TaskCommandRecord command, boolean commandSubmitted) {
        if (command == null || commandSubmitted || command.getExecuteNodeKey() == null) {
            return;
        }
        Instant now = Instant.now();
        taskCommandRepository.resetClaim(command.getId(), now);
        taskRepository.releaseClaimed(command.getTaskId(), now);
    }

    private int backoff(String message, int retryNum, Exception exception) {
        log.error("{}, retry count: {}", message, retryNum, exception);
        try {
            long backoffTime = retryStrategy.getBackoffTime(retryNum);
            log.info("Retrying after {}ms", backoffTime);
            sleepMillis(backoffTime);
            return retryNum + 1;
        } catch (MaxRetriesExceededException ex) {
            log.error("Max retries exceeded, resetting retry count", ex);
            return retryStrategy.reset();
        }
    }

    private void sleepMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
