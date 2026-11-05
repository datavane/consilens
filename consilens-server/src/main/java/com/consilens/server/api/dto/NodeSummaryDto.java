package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeSummaryDto {

    private String nodeKey;
    private String host;
    private int port;
    private String status;
    private double loadAverage;
    private double availableMemoryMb;
}
