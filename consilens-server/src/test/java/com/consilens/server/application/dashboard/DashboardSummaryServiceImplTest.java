package com.consilens.server.application.dashboard;

import com.consilens.server.api.dto.DashboardSummaryDto;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDashboardSummaryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2025-04-10T02:00:00Z"); // 2025-04-10 10:00 +08

    @Test
    void shouldAggregateDashboardSummary() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        ServerNodeQueryService nodeQueryService = mock(ServerNodeQueryService.class);

        when(taskRepository.countSubmittedSince(any(), any())).thenReturn(3L);
        when(taskRepository.countByStatuses(any())).thenReturn(2L);
        when(taskRepository.listSubmittedSince(any())).thenReturn(List.of(
                task("task-1", "2025-04-10T01:00:00Z"),
                task("task-2", "2025-04-09T02:00:00Z"),
                task("task-3", "2025-04-03T02:00:00Z")));
        when(artifactRepository.listCreatedSince(any())).thenReturn(List.of(
                result("artifact-1", "2025-04-10T01:30:00Z", 5L),
                result("artifact-2", "2025-04-10T01:35:00Z", 2L),
                result("artifact-3", "2025-04-09T03:00:00Z", 10L)));
        when(contentStore.read(any())).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            long count;
            if ("uri://artifact-1".equals(uri)) {
                count = 5L;
            } else if ("uri://artifact-2".equals(uri)) {
                count = 2L;
            } else {
                count = 10L;
            }
            return ("{\"differenceCount\":" + count + "}").getBytes(StandardCharsets.UTF_8);
        });
        when(nodeQueryService.listAliveNodes()).thenReturn(List.of(mock(), mock()));
        when(nodeQueryService.listNodes()).thenReturn(List.of(mock(), mock(), mock()));

        DashboardSummaryServiceImpl service = new DashboardSummaryServiceImpl(
                taskRepository,
                artifactRepository,
                contentStore,
                nodeQueryService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZONE));

        DashboardSummaryDto summary = service.getSummary("trace-1");

        assertEquals(3L, summary.getTodayTaskCount());
        assertEquals(2L, summary.getRunningTaskCount());
        assertEquals(2L, summary.getRecent7dTaskCount());
        assertEquals(17L, summary.getRecent7dDifferenceCount());
        assertEquals(2L, summary.getOnlineNodeCount());
        assertEquals(3L, summary.getTotalNodeCount());
        assertEquals(7, summary.getRecent7dTrend().size());
        assertEquals("2025-04-04", summary.getRecent7dTrend().get(0).getDate());
        assertEquals(0L, summary.getRecent7dTrend().get(0).getTaskCount());
        assertEquals("2025-04-09", summary.getRecent7dTrend().get(5).getDate());
        assertEquals(1L, summary.getRecent7dTrend().get(5).getTaskCount());
        assertEquals(10L, summary.getRecent7dTrend().get(5).getDifferenceCount());
        assertEquals("2025-04-10", summary.getRecent7dTrend().get(6).getDate());
        assertEquals(1L, summary.getRecent7dTrend().get(6).getTaskCount());
        assertEquals(7L, summary.getRecent7dTrend().get(6).getDifferenceCount());
    }

    @Test
    void shouldHandleMissingDifferenceCountInContent() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        ServerNodeQueryService nodeQueryService = mock(ServerNodeQueryService.class);

        when(taskRepository.listSubmittedSince(any())).thenReturn(List.of());
        when(artifactRepository.listCreatedSince(any())).thenReturn(List.of(
                result("artifact-1", "2025-04-10T01:30:00Z", 0L)));
        when(contentStore.read(any())).thenReturn("{\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        when(nodeQueryService.listAliveNodes()).thenReturn(List.of());
        when(nodeQueryService.listNodes()).thenReturn(List.of());

        DashboardSummaryServiceImpl service = new DashboardSummaryServiceImpl(
                taskRepository,
                artifactRepository,
                contentStore,
                nodeQueryService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZONE));

        DashboardSummaryDto summary = service.getSummary("trace-2");

        assertEquals(0L, summary.getRecent7dDifferenceCount());
        assertTrue(summary.getRecent7dTrend().stream().allMatch(point -> point.getDifferenceCount() == 0));
    }

    @Test
    void shouldNotReadOversizedLegacyArtifactContent() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        ArtifactContentStore contentStore = mock(ArtifactContentStore.class);
        ServerNodeQueryService nodeQueryService = mock(ServerNodeQueryService.class);

        when(taskRepository.listSubmittedSince(any())).thenReturn(List.of());
        when(artifactRepository.listCreatedSince(any())).thenReturn(List.of(
                result("artifact-large", "2025-04-10T01:30:00Z", 0L)));
        when(contentStore.size(any())).thenReturn(3L * 1024 * 1024);
        when(nodeQueryService.listAliveNodes()).thenReturn(List.of());
        when(nodeQueryService.listNodes()).thenReturn(List.of());

        DashboardSummaryServiceImpl service = new DashboardSummaryServiceImpl(
                taskRepository, artifactRepository, contentStore, nodeQueryService,
                new ObjectMapper(), Clock.fixed(NOW, ZONE));

        DashboardSummaryDto summary = service.getSummary("trace-large");

        assertEquals(0L, summary.getRecent7dDifferenceCount());
        verify(contentStore, never()).read(any());
    }

    private TaskInstanceRecord task(String instanceKey, String submitTime) {
        return TaskInstanceRecord.builder()
                .id(1L)
                .instanceKey(instanceKey)
                .status(TaskStatus.SUCCEEDED)
                .submitTime(Instant.parse(submitTime))
                .build();
    }

    private ArtifactRecord result(String artifactId, String createdAt, long unused) {
        return ArtifactRecord.builder()
                .id(artifactId)
                .artifactType(ArtifactKind.RUN_RESULT)
                .storageUri("uri://" + artifactId)
                .createdAt(Instant.parse(createdAt))
                .build();
    }
}
