package com.consilens.server.domain.model;

import com.consilens.server.domain.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskInstanceRecord {

    private Long id;
    private String instanceKey;
    private Long definitionId;
    private String serialNo;
    private String tenantId;
    private String traceId;
    private String correlationId;
    private String requestPayload;
    private String requestHash;
    private TaskStatus status;
    private Integer priority;
    private Instant scheduleTime;
    private Instant submitTime;
    private Instant startTime;
    private Instant endTime;
    private String executeNodeKey;
    private String resultArtifactId;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetryCount;
    private Instant createdAt;
    private Instant updatedAt;
}
