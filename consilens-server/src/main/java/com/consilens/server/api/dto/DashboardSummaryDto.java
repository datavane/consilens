package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryDto {

    private long todayTaskCount;
    private long runningTaskCount;
    private long recent7dTaskCount;
    private long recent7dDifferenceCount;
    private long onlineNodeCount;
    private long totalNodeCount;
    private List<DashboardTrendPoint> recent7dTrend;
}
