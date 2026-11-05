package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Definition detail: full config plus the most recent run instances.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDefinitionDetailDto {

    private String id;
    private String name;
    private String description;
    private String taskType;
    private Boolean enabled;
    private Instant lastRunAt;
    private Instant createdAt;
    private Instant updatedAt;

    private Map<String, Object> config;

    private List<RecentInstanceDto> recentInstances;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentInstanceDto {
        private String instanceId;
        private String serialNo;
        private String status;
        private Instant submitTime;
        private Instant endTime;
    }
}
