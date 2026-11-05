package com.consilens.server.application.capability;

import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.api.dto.SyncArtifactResponse;
import com.consilens.server.api.dto.ValidateRequest;

public interface SynchronousCapabilityService {

    SyncArtifactResponse plan(PlanRequest request, String traceId);

    SyncArtifactResponse validate(ValidateRequest request, String traceId);

    SyncArtifactResponse diagnose(DiagnoseRequest request, String traceId);

    SyncArtifactResponse repair(RepairRequest request, String traceId);
}
