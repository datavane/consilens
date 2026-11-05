package com.consilens.cluster.api;

import com.consilens.connector.api.planner.CompareRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Portable application submission envelope containing no connector configuration or secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterSubmitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String submissionId;

    private ClusterComparisonDescription comparison;

    private ClusterExecutionDescription execution;

    private YarnSubmissionSpec yarnSubmission;

    private KubernetesSubmissionSpec kubernetesSubmission;

    public static ClusterSubmitRequest from(String submissionId,
                                            CompareRequest compareRequest,
                                            ClusterComparisonDescription comparison) {
        if (compareRequest == null) {
            throw new IllegalArgumentException("compareRequest is required for cluster submission");
        }
        return ClusterSubmitRequest.builder()
                .submissionId(submissionId)
                .comparison(comparison)
                .execution(ClusterExecutionDescription.from(compareRequest.getClusterExecutionSpec()))
                .build();
    }

    public void validate() {
        if (isBlank(submissionId)) {
            throw new IllegalArgumentException("submissionId is required for cluster submission");
        }
        if (comparison == null || isBlank(comparison.getSourceConfigRef()) || isBlank(comparison.getTargetConfigRef())) {
            throw new IllegalArgumentException("sourceConfigRef and targetConfigRef are required for cluster submission");
        }
        if (execution == null || execution.getExecutionMode() == null) {
            throw new IllegalArgumentException("executionMode is required for cluster submission");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
