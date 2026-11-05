package com.consilens.cluster.runtime;

import com.consilens.connector.api.planner.ExecutionMode;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Public, credential-free description of a successfully completed local submission.
 */
@Value
@Builder
public class LocalExecutionManifest {
    String formatVersion;
    String submissionId;
    ExecutionMode executionMode;
    List<SplitAttemptRecord> attempts;
    List<String> successfulSplitIds;
    long sourceRowCount;
    long targetRowCount;
    long totalDifferences;
}
