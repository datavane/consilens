package com.consilens.server.application.dashboard;

import com.consilens.server.api.dto.DashboardSummaryDto;

public interface DashboardSummaryService {

    DashboardSummaryDto getSummary(String traceId);
}
