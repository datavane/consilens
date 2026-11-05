package com.consilens.server.domain.model;

import com.consilens.server.domain.enums.TaskCommandStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCommandRecord {

    private Long id;
    private String commandKey;
    private Long taskId;
    private String shardKey;
    private Integer shardSlot;
    private TaskCommandStatus status;
    private String executeNodeKey;
    private Instant lockTime;
    private Instant lockUntil;
    private Instant scheduleTime;
    private Instant createdAt;
    private Instant updatedAt;
}
