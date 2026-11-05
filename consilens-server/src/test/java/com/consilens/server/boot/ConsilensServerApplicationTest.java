package com.consilens.server.boot;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.SyncArtifactResponse;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.ServerCapabilityFacade;
import com.consilens.server.application.capability.SynchronousCapabilityService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.db.entity.TaskCommandEntity;
import com.consilens.server.infrastructure.db.service.ArtifactPersistenceService;
import com.consilens.server.infrastructure.db.service.TaskCommandService;
import com.consilens.server.infrastructure.db.service.TaskService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.profiles.active=local")
@org.springframework.test.context.TestPropertySource(properties = {
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class ConsilensServerApplicationTest {

    @Autowired
    private RunTaskSubmissionService runTaskSubmissionService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskCommandService taskCommandService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactPersistenceService artifactPersistenceService;

    @Autowired
    private SynchronousCapabilityService synchronousCapabilityService;

    @Autowired
    private ServerCapabilityFacade serverCapabilityFacade;

    @Autowired
    private RunTaskQueryService runTaskQueryService;

    @Test
    void shouldLoadContext() {
    }

    @Test
    void shouldSubmitRunTaskWithMybatisPlusPersistence() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-" + UUID.randomUUID());
        request.setConfigArtifactId("artifact-config");

        TaskAcceptedResponse firstResponse = runTaskSubmissionService.submit(request, "trace-1");
        TaskAcceptedResponse secondResponse = runTaskSubmissionService.submit(request, "trace-2");

        assertThat(secondResponse.getTaskId()).isEqualTo(firstResponse.getTaskId());

        Optional<TaskInstanceRecord> task = taskRepository.findBySerialNo(request.getSerialNo());
        assertThat(task).isPresent();
        assertThat(task.get().getInstanceKey()).isEqualTo(firstResponse.getTaskId());

        long commandCount = taskCommandService.count(new QueryWrapper<TaskCommandEntity>().lambda()
                .eq(TaskCommandEntity::getTaskId, task.get().getId()));
        Assertions.assertEquals(1L, commandCount);
    }

    @Test
    void shouldPersistMaxLengthTraceIdForTaskAndArtifact() {
        String traceId = "t".repeat(128);
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-trace-" + UUID.randomUUID());
        request.setConfigArtifactId("artifact-config");

        TaskAcceptedResponse accepted = runTaskSubmissionService.submit(request, traceId);
        TaskInstanceRecord task = taskRepository.resolveByRef(accepted.getTaskId()).orElseThrow();

        assertThat(task.getTraceId()).isEqualTo(traceId);

        ArtifactRefDto artifact = artifactService.writeArtifact(
                TaskExecutionContext.builder()
                        .taskId(task.getId())
                        .instanceKey(task.getInstanceKey())
                        .traceId(traceId)
                        .build(),
                ArtifactKind.RUN_RESULT,
                "json",
                Map.of("success", true),
                Map.of());

        assertThat(artifactPersistenceService.getById(artifact.getId()).getTraceId()).isEqualTo(traceId);
    }

    @Test
    void shouldCreatePlanValidateRunDiagnoseAndRepairArtifacts() {
        PlanRequest planRequest = new PlanRequest();
        planRequest.setGoal("compare orders");
        PlanRequest.Endpoint source = new PlanRequest.Endpoint();
        source.setType("mysql");
        source.setTable("orders");
        source.setQuery("SELECT * FROM orders");
        planRequest.setSource(source);
        PlanRequest.Endpoint target = new PlanRequest.Endpoint();
        target.setType("postgresql");
        target.setTable("orders");
        target.setQuery("SELECT * FROM orders");
        planRequest.setTarget(target);
        planRequest.setKeys(List.of("id"));
        planRequest.getHints().put("sourceConnection", connection());
        planRequest.getHints().put("targetConnection", connection());

        SyncArtifactResponse plan = synchronousCapabilityService.plan(planRequest, "trace-plan");
        assertThat(plan.getArtifact().getType()).isEqualTo("CONFIG");

        ValidateRequest validateRequest = new ValidateRequest();
        validateRequest.setConfigArtifactId(plan.getArtifact().getId());
        SyncArtifactResponse validate = synchronousCapabilityService.validate(validateRequest, "trace-validate");
        assertThat(validate.getArtifact().getType()).isEqualTo("VALIDATION_RESULT");

        RunRequest runRequest = new RunRequest();
        runRequest.setSerialNo("serial-dry-run-" + UUID.randomUUID());
        runRequest.setConfigArtifactId(plan.getArtifact().getId());
        RunRequest.Options options = new RunRequest.Options();
        options.setDryRun(true);
        runRequest.setOptions(options);
        ArtifactRefDto run = serverCapabilityFacade.run(runRequest, context("trace-run"));
        assertThat(run.getType()).isEqualTo("RUN_RESULT");

        DiagnoseRequest diagnoseRequest = new DiagnoseRequest();
        diagnoseRequest.setRunArtifactId(run.getId());
        SyncArtifactResponse diagnose = synchronousCapabilityService.diagnose(diagnoseRequest, "trace-diagnose");
        assertThat(diagnose.getArtifact().getType()).isEqualTo("DIAGNOSIS");

        RepairRequest repairRequest = new RepairRequest();
        repairRequest.setDiagnosisArtifactId(diagnose.getArtifact().getId());
        SyncArtifactResponse repair = synchronousCapabilityService.repair(repairRequest, "trace-repair");
        assertThat(repair.getArtifact().getType()).isEqualTo("REPAIR_CONFIG");

        long artifactCount = artifactPersistenceService.count();
        assertThat(artifactCount).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void shouldRejectSerialNoConflictWithDifferentPayload() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-conflict-" + UUID.randomUUID());
        request.setConfigArtifactId("artifact-a");
        runTaskSubmissionService.submit(request, "trace-1");

        RunRequest conflict = new RunRequest();
        conflict.setSerialNo(request.getSerialNo());
        conflict.setConfigArtifactId("artifact-b");

        Assertions.assertThrows(RuntimeException.class, () -> runTaskSubmissionService.submit(conflict, "trace-2"));
    }

    @Test
    void shouldReturnNextActionsForSucceededRunTask() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-query-" + UUID.randomUUID());
        request.setConfigArtifactId("artifact-config-query");
        TaskAcceptedResponse accepted = runTaskSubmissionService.submit(request, "trace-query");
        TaskInstanceRecord task = taskRepository.resolveByRef(accepted.getTaskId()).orElseThrow();

        ArtifactRefDto artifact = artifactService.writeArtifact(
                TaskExecutionContext.builder()
                        .taskId(task.getId())
                        .instanceKey(task.getInstanceKey())
                        .traceId("trace-query")
                        .build(),
                ArtifactKind.RUN_RESULT,
                "json",
                Map.of("success", true, "differenceCount", 0),
                Map.of("source", "test"));
        taskRepository.updateClaimed(task.getId(), "test-node", java.time.Instant.now());
        taskRepository.updateRunning(task.getId(), "test-node", java.time.Instant.now());
        taskRepository.updateSuccess(task.getId(), artifact.getId(), java.time.Instant.now());

        TaskQueryResponse response = runTaskQueryService.getTask(accepted.getTaskId(), "trace-query");
        assertThat(response.getAvailableNextActions()).containsExactly("diagnose", "repair");
    }

    @Test
    void shouldReleaseExpiredClaimBackToPending() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-release-" + UUID.randomUUID());
        request.setConfigArtifactId("artifact-config-release");
        runTaskSubmissionService.submit(request, "trace-release");
        TaskInstanceRecord task = taskRepository.findBySerialNo(request.getSerialNo()).orElseThrow();
        TaskCommandEntity command = taskCommandService.getOne(new QueryWrapper<TaskCommandEntity>().lambda()
                .eq(TaskCommandEntity::getTaskId, task.getId()), false);

        LocalDateTime now = LocalDateTime.now();
        boolean claimed = taskCommandService.claim(command.getId(),
                "expired-node",
                now.minusSeconds(20),
                now.minusSeconds(10));
        assertThat(claimed).isTrue();

        List<TaskCommandEntity> expiredCommands = taskCommandService.listExpiredClaims(now, 10);
        assertThat(expiredCommands).extracting(TaskCommandEntity::getTaskId).contains(task.getId());
        boolean released = taskCommandService.release(command.getId(), now);
        assertThat(released).isTrue();
        TaskCommandEntity releasedCommand = taskCommandService.getById(command.getId());
        assertThat(releasedCommand.getStatus().name()).isEqualTo("RELEASED");
        assertThat(releasedCommand.getExecuteNodeKey()).isNull();
    }

    @Test
    void shouldReturnEmptyListsForNonPositiveSchedulerQueryLimits() {
        assertThat(taskCommandService.listExpiredClaims(LocalDateTime.now(), 0)).isEmpty();
        assertThat(taskCommandService.listClaimedByExecuteNode("node-a", 0)).isEmpty();
        assertThat(taskService.listByExecuteNodeAndStatuses("node-a", List.of(TaskStatus.RUNNING), 0)).isEmpty();
        assertThat(taskService.listByStatusesExcludingExecuteNodes(List.of(TaskStatus.RUNNING), Set.of("node-a"), 0))
                .isEmpty();
    }

    private TaskExecutionContext context(String traceId) {
        return TaskExecutionContext.builder()
                .traceId(traceId)
                .nodeKey("test-node")
                .build();
    }

    private Map<String, Object> connection() {
        return Map.of("url", "jdbc:h2:mem:test", "username", "sa");
    }
}
