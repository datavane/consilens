package com.consilens.server.application.task;

import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.application.task.impl.RunTaskQueryServiceImpl;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.model.TaskPage;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTaskQueryServiceImplTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    private final ArtifactContentStore contentStore = mock(ArtifactContentStore.class);

    private RunTaskQueryServiceImpl service() {
        return new RunTaskQueryServiceImpl(taskRepository, artifactRepository, contentStore,
                mock(TaskDefinitionRepository.class), new ObjectMapper());
    }

    @Test
    void shouldResolveTaskByNumericId() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(123456789012345678L)
                .instanceKey("inst_1")
                .serialNo("serial-1")
                .status(TaskStatus.SUCCEEDED)
                .traceId("trace-1")
                .build();
        when(taskRepository.resolveByRef("123456789012345678")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(123456789012345678L)).thenReturn(List.of());

        TaskQueryResponse response = service().getTask("123456789012345678", "trace-x");

        assertEquals("inst_1", response.getTaskId());
        assertEquals("serial-1", response.getSerialNo());
    }

    @Test
    void shouldSkipLargeLegacyArtifactWhenBuildingListDiffSummary() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(2L)
                .instanceKey("task-large")
                .serialNo("serial-large")
                .status(TaskStatus.SUCCEEDED)
                .build();
        when(taskRepository.listTaskPage(1, 20, null, null, null, null, null, null))
                .thenReturn(new TaskPage(1, List.of(task)));
        ArtifactRecord artifact = ArtifactRecord.builder()
                .id("artifact-large")
                .taskId(2L)
                .artifactType(ArtifactKind.RUN_RESULT)
                .storageUri("file:/tmp/large-run-result.json")
                .differenceCount(null)
                .build();
        when(artifactRepository.listByTaskId(2L)).thenReturn(List.of(artifact));
        when(contentStore.size("file:/tmp/large-run-result.json")).thenReturn(3L * 1024 * 1024);

        var response = service().listTasks(1, 20, null, null, null, null, null, true, null, "trace-x");

        assertEquals(1, response.getItems().size());
        assertNull(response.getItems().get(0).getDiffSummary());
        verify(contentStore, never()).read(anyString());
    }

    @Test
    void shouldNotExposeDifferenceStoragePath() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(3L)
                .instanceKey("task-path")
                .serialNo("serial-path")
                .status(TaskStatus.SUCCEEDED)
                .build();
        when(taskRepository.resolveByRef("task-path")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(3L)).thenReturn(List.of(ArtifactRecord.builder()
                .id("artifact-path")
                .taskId(3L)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("/srv/consilens/.artifacts/run-result.json")
                .differencesUri("/srv/consilens/.artifacts/run-result.jsonl")
                .differenceRows(5L)
                .build()));

        TaskQueryResponse response = service().getTask("task-path", "trace-x");

        assertNull(response.getArtifacts().get(0).getDifferencesUri());
        assertEquals(Boolean.TRUE, response.getArtifacts().get(0).getDifferenceDetailsAvailable());
    }

    @Test
    void shouldExposeFullTaskFieldsInGetTask() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(1L)
                .instanceKey("task-1")
                .serialNo("serial-1")
                .status(TaskStatus.SUCCEEDED)
                .traceId("trace-1")
                .executeNodeKey("node-1")
                .retryCount(2)
                .submitTime(Instant.parse("2026-01-01T00:00:00Z"))
                .startTime(Instant.parse("2026-01-01T00:00:01Z"))
                .endTime(Instant.parse("2026-01-01T00:00:02Z"))
                .build();
        when(taskRepository.resolveByRef("task-1")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(1L)).thenReturn(List.of());

        TaskQueryResponse response = service().getTask("task-1", "trace-x");

        assertEquals("task-1", response.getTaskId());
        assertEquals("serial-1", response.getSerialNo());
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals("trace-1", response.getTraceId());
        assertEquals("node-1", response.getExecuteNodeKey());
        assertEquals(2, response.getRetryCount());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), response.getSubmitTime());
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), response.getStartTime());
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), response.getEndTime());
    }

    @Test
    void shouldExposeFailureReasonAndMaskCredentials() {
        TaskInstanceRecord task = TaskInstanceRecord.builder()
                .id(4L)
                .instanceKey("task-failed")
                .serialNo("serial-failed")
                .status(TaskStatus.FAILED)
                .errorCode("CLUSTER_EXECUTION_FAILED")
                .errorMessage("cluster application default/job finished with FAILED: "
                        + "jdbc:mysql://root:secret@mysql:3306/consilens Connection refused")
                .build();
        when(taskRepository.resolveByRef("task-failed")).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(4L)).thenReturn(List.of());

        TaskQueryResponse response = service().getTask("task-failed", "trace-x");

        assertEquals("CLUSTER_EXECUTION_FAILED", response.getErrorCode());
        assertTrue(response.getErrorMessage().contains("Connection refused"), response.getErrorMessage());
        assertFalse(response.getErrorMessage().contains("secret"), response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("***"), response.getErrorMessage());
    }
}
