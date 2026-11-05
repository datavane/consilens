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
public class TaskExecutionContext {

    private String instanceKey;
    private Long taskId;
    private String traceId;
    private String tenantId;
    private String nodeKey;
    private Instant startTime;
}
