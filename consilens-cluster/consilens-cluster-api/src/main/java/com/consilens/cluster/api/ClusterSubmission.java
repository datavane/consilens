package com.consilens.cluster.api;

import com.consilens.connector.api.planner.ExecutionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Submission receipt returned after a cluster backend accepts a comparison.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    private String submissionId;

    private ExecutionMode executionMode;

    private Instant submittedAt;

    private Integer splitCount;

    private String clusterApplicationId;
}
