package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Typed execution settings shared by cluster submission backends.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterExecutionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    @Builder.Default
    private ExecutionMode executionMode = ExecutionMode.LOCAL;

    private Integer parallelism;

    private Integer maxAttempts;

    private String yarnQueue;

    private String kubernetesNamespace;

    private String kubernetesImage;

    private Map<String, String> attributes;
}
