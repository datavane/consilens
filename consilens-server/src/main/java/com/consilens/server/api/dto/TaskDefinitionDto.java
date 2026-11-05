package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDefinitionDto {

    private String id;
    private String name;
    private String description;
    private String taskType;
    private Boolean enabled;
    private Instant lastRunAt;
    private Instant createdAt;
    private Instant updatedAt;
}
