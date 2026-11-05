package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

/**
 * Input for a single local simulated cluster submission.
 */
@Value
@Builder
public class LocalSimulationRequest {
    String submissionId;
    ClusterExecutionSpec executionSpec;
    List<LocalSplitTask> splitTasks;
    Path manifestDirectory;
}
