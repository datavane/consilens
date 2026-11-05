package com.consilens.server.application.dashboard;

import com.consilens.server.api.dto.DashboardSummaryDto;
import com.consilens.server.api.dto.DashboardTrendPoint;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.application.topology.ServerNodeQueryService;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class DashboardSummaryServiceImpl implements DashboardSummaryService {

    private static final Set<TaskStatus> RUNNING_STATUSES = EnumSet.of(
            TaskStatus.PENDING,
            TaskStatus.CLAIMED,
            TaskStatus.RUNNING,
            TaskStatus.CANCEL_REQUESTED);
    private static final int TREND_DAYS = 7;
    /** 旧 artifact 没有 difference_count 时才读 content；大文件直接跳过，避免工作台被拖慢。 */
    private static final long MAX_LEGACY_CONTENT_BYTES = 2L * 1024 * 1024;
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ServerNodeQueryService serverNodeQueryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DashboardSummaryServiceImpl(TaskRepository taskRepository,
                                          ArtifactRepository artifactRepository,
                                          ArtifactContentStore artifactContentStore,
                                          ServerNodeQueryService serverNodeQueryService,
                                          ObjectMapper objectMapper) {
        this(taskRepository,
                artifactRepository,
                artifactContentStore,
                serverNodeQueryService,
                objectMapper,
                Clock.systemDefaultZone());
    }

    DashboardSummaryServiceImpl(TaskRepository taskRepository,
                                   ArtifactRepository artifactRepository,
                                   ArtifactContentStore artifactContentStore,
                                   ServerNodeQueryService serverNodeQueryService,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.serverNodeQueryService = serverNodeQueryService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public DashboardSummaryDto getSummary(String traceId) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        Instant tomorrowStart = today.plusDays(1L).atStartOfDay(zone).toInstant();
        Instant trendStart = today.minusDays(TREND_DAYS - 1L).atStartOfDay(zone).toInstant();

        long todayTaskCount = taskRepository.countSubmittedSince(todayStart, tomorrowStart);
        long runningTaskCount = taskRepository.countByStatuses(RUNNING_STATUSES);

        Map<LocalDate, Long> taskByDay = new TreeMap<>();
        for (TaskInstanceRecord task : taskRepository.listSubmittedSince(trendStart)) {
            if (task.getSubmitTime() == null) {
                continue;
            }
            LocalDate day = task.getSubmitTime().atZone(zone).toLocalDate();
            taskByDay.merge(day, 1L, Long::sum);
        }

        Map<LocalDate, Long> differenceByDay = new TreeMap<>();
        for (ArtifactRecord artifact : artifactRepository.listCreatedSince(trendStart)) {
            if (artifact.getArtifactType() != ArtifactKind.RUN_RESULT) {
                continue;
            }
            LocalDate day = artifact.getCreatedAt() == null ? null : artifact.getCreatedAt().atZone(zone).toLocalDate();
            if (day == null) {
                continue;
            }
            long differenceCount = differenceCountOf(artifact);
            if (differenceCount > 0) {
                differenceByDay.merge(day, differenceCount, Long::sum);
            }
        }

        List<DashboardTrendPoint> trend = new ArrayList<>(TREND_DAYS);
        long recent7dTaskCount = 0;
        long recent7dDifferenceCount = 0;
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            long taskCount = taskByDay.getOrDefault(day, 0L);
            long differenceCount = differenceByDay.getOrDefault(day, 0L);
            recent7dTaskCount += taskCount;
            recent7dDifferenceCount += differenceCount;
            trend.add(DashboardTrendPoint.builder()
                    .date(day.format(DAY_FORMATTER))
                    .taskCount(taskCount)
                    .differenceCount(differenceCount)
                    .build());
        }

        return DashboardSummaryDto.builder()
                .todayTaskCount(todayTaskCount)
                .runningTaskCount(runningTaskCount)
                .recent7dTaskCount(recent7dTaskCount)
                .recent7dDifferenceCount(recent7dDifferenceCount)
                .onlineNodeCount(serverNodeQueryService.listAliveNodes().size())
                .totalNodeCount(serverNodeQueryService.listNodes().size())
                .recent7dTrend(trend)
                .build();
    }

    private long differenceCountOf(ArtifactRecord artifact) {
        // 新链路统计已落 DB（含集群回传的统计），优先读列；旧 artifact 再回退读 content
        if (artifact.getDifferenceCount() != null) {
            return artifact.getDifferenceCount();
        }
        if (artifact.getStorageUri() == null) {
            return 0L;
        }
        try {
            if (artifactContentStore.size(artifact.getStorageUri()) > MAX_LEGACY_CONTENT_BYTES) {
                return 0L;
            }
            byte[] content = artifactContentStore.read(artifact.getStorageUri());
            JsonNode root = objectMapper.readTree(content);
            JsonNode value = root.get("differenceCount");
            return value != null && value.isNumber() ? value.asLong() : 0L;
        } catch (Exception exception) {
            return 0L;
        }
    }
}
