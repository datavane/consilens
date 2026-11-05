package com.consilens.server.application.task;

import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.domain.enums.TaskStatus;

import java.time.Instant;
import java.util.Collection;

public interface RunTaskQueryService {

    TaskQueryResponse getTask(String taskId, String traceId);

    PageResponse<TaskSummaryDto> listTasks(int page,
                                           int pageSize,
                                           Collection<TaskStatus> statuses,
                                           String keyword,
                                           String executeNodeKey,
                                           Instant startTime,
                                           Instant endTime,
                                           boolean includeDiffSummary,
                                           Long definitionId,
                                           String traceId);
}
