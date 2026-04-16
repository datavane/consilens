package com.consilens.core.compare.plan;

import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.core.compare.CompareExecutionSettings;
import com.consilens.core.compare.ComparePlan;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ServerJoinPlan implements ComparePlan {

    private final CompareExecutionSettings executionSettings;

    @Override
    public String getPlanType() {
        return ComparePlanTypes.SERVER_JOIN;
    }
}
