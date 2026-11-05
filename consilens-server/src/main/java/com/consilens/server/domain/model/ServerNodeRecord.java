package com.consilens.server.domain.model;

import com.consilens.server.domain.enums.ServerNodeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerNodeRecord {

    private Long id;
    private String nodeKey;
    private String host;
    private int port;
    private String machineCode;
    private ServerNodeStatus status;
    private double loadAverage;
    private double availableMemoryMb;
    private Instant heartbeatTime;
    private Instant statusUpdateTime;
    private String statusUpdateBy;
    private Instant createdAt;
    private Instant updatedAt;
}
