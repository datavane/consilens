package com.consilens.server.application.taskdefinition;

import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;

public interface TaskDefinitionService {

    TaskDefinitionDto create(TaskDefinitionCreateRequest request);

    PageResponse<TaskDefinitionDto> list(int page, int pageSize, String keyword, Boolean enabled);

    TaskDefinitionDetailDto get(Long id);

    TaskDefinitionDto update(Long id, TaskDefinitionCreateRequest request);

    void delete(Long id);

    /** Submit one run instance from the definition config; updates last_run_at. */
    TaskAcceptedResponse run(Long id, TaskDefinitionRunRequest request, String traceId);

    TaskDefinitionDto toggle(Long id, boolean enabled);
}
