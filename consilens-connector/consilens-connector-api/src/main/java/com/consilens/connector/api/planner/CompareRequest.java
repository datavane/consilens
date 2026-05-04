package com.consilens.connector.api.planner;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.normalization.NormalizationSpec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareRequest {

    private ConnectorConfig source;

    private ConnectorConfig target;

    private KeySpec sourceKeySpec;

    private KeySpec targetKeySpec;

    private ComparisonSpec sourceComparisons;

    private ComparisonSpec targetComparisons;

    private PredicateSpec sourceFilter;

    private PredicateSpec targetFilter;

    private RealtimeSpec realtimeSpec;

    private NormalizationSpec normalizationSpec;

    private CompareStrategyPreference strategyPreference;

    private CompareExecutionOptions executionOptions;
}
