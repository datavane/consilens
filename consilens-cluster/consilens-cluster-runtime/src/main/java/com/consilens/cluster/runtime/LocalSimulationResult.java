package com.consilens.cluster.runtime;

import com.consilens.core.diff.DiffResult;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

/**
 * Result returned by a successful local simulated submission.
 */
@Value
@Builder
public class LocalSimulationResult {
    DiffResult diffResult;
    Path manifestPath;
    List<SplitAttemptRecord> attempts;
}
