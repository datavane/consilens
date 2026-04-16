package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareStrategyPreference {

    private List<String> preferredPlans;

    private Boolean allowFallback;

    private Map<String, Object> options;
}
