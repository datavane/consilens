package com.consilens.server.application.task;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;

public interface RunTaskSubmissionService {

    TaskAcceptedResponse submit(RunRequest request, String traceId);
}
