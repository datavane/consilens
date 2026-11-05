package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ValidateRequest;
import com.consilens.server.domain.model.TaskExecutionContext;

public interface ServerCapabilityFacade {

    ArtifactRefDto plan(PlanRequest request, TaskExecutionContext context);

    ArtifactRefDto validate(ValidateRequest request, TaskExecutionContext context);

    ArtifactRefDto run(RunRequest request, TaskExecutionContext context);

    ArtifactRefDto diagnose(DiagnoseRequest request, TaskExecutionContext context);

    ArtifactRefDto repair(RepairRequest request, TaskExecutionContext context);
}
