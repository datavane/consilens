package com.consilens.connector.api.normalization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationRule {

    private MatchSpec match;

    private String operation;

    private Map<String, Object> params;
}
