package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDefinitionRecord {

    private Long id;
    private String definitionKey;
    private String name;
    private String description;
    private String taskType;
    private String config;
    private Boolean enabled;
    private String scheduleType;
    private String cronExpr;
    private Instant lastRunAt;
    private Instant createdAt;
    private Instant updatedAt;
}
