package com.consilens.cli.ai;

import lombok.Builder;
import lombok.Value;

/**
 * Request for generating a Consilens CLI configuration from AI and explicit hints.
 */
@Value
@Builder
public class AIConfigRequest {

    String goal;
    String sourceType;
    String sourceUrl;
    String sourceName;
    String sourceTable;
    String sourceQuery;
    String sourceUserEnv;
    String sourcePasswordEnv;
    String targetType;
    String targetUrl;
    String targetName;
    String targetTable;
    String targetQuery;
    String targetUserEnv;
    String targetPasswordEnv;
    String keys;
    String sourceKeys;
    String targetKeys;
    String fields;
    String sourceFields;
    String targetFields;
    String strategyMode;
    String algorithm;
    Integer bisectionFactor;
    Long bisectionThreshold;
    Integer batchSize;
    Long maxDifferences;
    AIBackendOptions backendOptions;
}
