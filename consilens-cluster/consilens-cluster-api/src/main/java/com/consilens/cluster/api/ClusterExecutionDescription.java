package com.consilens.cluster.api;

import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Portable execution settings excluding free-form attributes and connector secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterExecutionDescription implements Serializable {

    private static final long serialVersionUID = 1L;

    private ExecutionMode executionMode;

    private Integer parallelism;

    private Integer maxAttempts;

    private String yarnQueue;

    private String kubernetesNamespace;

    private String kubernetesImage;

    public static ClusterExecutionDescription from(ClusterExecutionSpec executionSpec) {
        if (executionSpec == null) {
            throw new IllegalArgumentException("clusterExecutionSpec is required for cluster submission");
        }
        return ClusterExecutionDescription.builder()
                .executionMode(executionSpec.getExecutionMode())
                .parallelism(executionSpec.getParallelism())
                .maxAttempts(executionSpec.getMaxAttempts())
                .yarnQueue(executionSpec.getYarnQueue())
                .kubernetesNamespace(executionSpec.getKubernetesNamespace())
                .kubernetesImage(executionSpec.getKubernetesImage())
                .build();
    }
}
