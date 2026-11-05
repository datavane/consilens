package com.consilens.cluster.runtime;

import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.connector.api.planner.ExecutionMode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * LOCAL implementation of the portable cluster submission SPI.
 *
 * <p>The portable request is deliberately limited to metadata. The task provider keeps the
 * executable {@code CompareRequest} in process and never copies it into the request or manifest.
 */
public class LocalClusterSubmitter implements ClusterSubmitter {

    private final LocalSubmissionTaskProvider taskProvider;
    private final LocalSimulatedClusterRuntime runtime;
    private final Map<String, LocalSimulationResult> completedResults = new LinkedHashMap<>();

    public LocalClusterSubmitter(LocalSubmissionTaskProvider taskProvider) {
        this(taskProvider, new LocalSimulatedClusterRuntime());
    }

    LocalClusterSubmitter(LocalSubmissionTaskProvider taskProvider, LocalSimulatedClusterRuntime runtime) {
        this.taskProvider = Objects.requireNonNull(taskProvider, "taskProvider");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        validateLocalRequest(request);
        try {
            LocalSimulationResult result = runtime.execute(taskProvider.resolve(request));
            synchronized (completedResults) {
                completedResults.put(request.getSubmissionId(), result);
            }
            return ClusterSubmission.builder()
                    .submissionId(request.getSubmissionId())
                    .executionMode(ExecutionMode.LOCAL)
                    .submittedAt(Instant.now())
                    .splitCount(successfulSplitCount(result))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Local submission failed: " + request.getSubmissionId(), e);
        }
    }

    public Optional<LocalSimulationResult> findResult(String submissionId) {
        synchronized (completedResults) {
            return Optional.ofNullable(completedResults.get(submissionId));
        }
    }

    private void validateLocalRequest(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("local submission request is required");
        }
        request.validate();
        if (request.getExecution().getExecutionMode() != ExecutionMode.LOCAL) {
            throw new IllegalArgumentException("Local submitter only supports LOCAL execution mode");
        }
    }

    private int successfulSplitCount(LocalSimulationResult result) {
        return (int) result.getAttempts().stream()
                .filter(attempt -> attempt.getState() == SplitAttemptState.SUCCEEDED)
                .count();
    }
}
