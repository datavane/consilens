package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionResult {

    private String artifactId;
    private String artifactType;
    private String artifactFormat;
    private Map<String, Object> metadata;
}
