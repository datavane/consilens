package com.consilens.cli.ai;

import lombok.Builder;
import lombok.Value;

/**
 * Backend selection options shared by AI CLI commands.
 */
@Value
@Builder
public class AIBackendOptions {

    @Builder.Default
    String backend = "noop";

    String model;
    String baseUrl;
    String apiKey;
    String timeout;
    Double temperature;
    Integer maxTokens;

    @Builder.Default
    boolean noLlm = false;
}
