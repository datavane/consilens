package com.consilens.cli.command;

import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.cli.ai.AIConfigRequest;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shared options for AI commands that generate a Consilens config draft.
 */
class AIConfigCliOptions {

    @Parameters(index = "0", arity = "0..1", description = "Natural language diff goal")
    String goal;

    @Option(names = "--backend", description = "AI backend: noop, ollama, openai, deepseek. Defaults to CONSILENS_AI_BACKEND or noop")
    String backend;

    @Option(names = "--model", description = "AI model name")
    String model;

    @Option(names = "--base-url", description = "AI backend base URL")
    String baseUrl;

    @Option(names = "--api-key", description = "AI backend API key")
    String apiKey;

    @Option(names = "--timeout", description = "AI backend timeout")
    String timeout;

    @Option(names = "--temperature", description = "AI sampling temperature")
    Double temperature;

    @Option(names = "--max-tokens", description = "AI max output tokens")
    Integer maxTokens;

    @Option(names = "--no-llm", description = "Do not call an LLM; only use explicit CLI hints")
    boolean noLlm;

    @Option(names = "--source-type", description = "Source connector type")
    String sourceType;

    @Option(names = "--source-url", description = "Source JDBC URL")
    String sourceUrl;

    @Option(names = "--source-name", description = "Source logical name")
    String sourceName;

    @Option(names = "--source-table", description = "Source table name")
    String sourceTable;

    @Option(names = "--source-query", description = "Source SELECT/WITH query")
    String sourceQuery;

    @Option(names = "--source-user-env", defaultValue = "SOURCE_USERNAME", description = "Source username env var name")
    String sourceUserEnv;

    @Option(names = "--source-password-env", defaultValue = "SOURCE_PASSWORD", description = "Source password env var name")
    String sourcePasswordEnv;

    @Option(names = "--target-type", description = "Target connector type")
    String targetType;

    @Option(names = "--target-url", description = "Target JDBC URL")
    String targetUrl;

    @Option(names = "--target-name", description = "Target logical name")
    String targetName;

    @Option(names = "--target-table", description = "Target table name")
    String targetTable;

    @Option(names = "--target-query", description = "Target SELECT/WITH query")
    String targetQuery;

    @Option(names = "--target-user-env", defaultValue = "TARGET_USERNAME", description = "Target username env var name")
    String targetUserEnv;

    @Option(names = "--target-password-env", defaultValue = "TARGET_PASSWORD", description = "Target password env var name")
    String targetPasswordEnv;

    @Option(names = "--keys", description = "Comma-separated key columns used on both sides")
    String keys;

    @Option(names = "--source-keys", description = "Comma-separated source key columns")
    String sourceKeys;

    @Option(names = "--target-keys", description = "Comma-separated target key columns")
    String targetKeys;

    @Option(names = "--fields", description = "Comma-separated fields used on both sides")
    String fields;

    @Option(names = "--source-fields", description = "Comma-separated source fields")
    String sourceFields;

    @Option(names = "--target-fields", description = "Comma-separated target fields")
    String targetFields;

    @Option(names = "--strategy-mode", defaultValue = "checksum", description = "Strategy mode: checksum or join")
    String strategyMode;

    @Option(names = "--algorithm", defaultValue = "xor", description = "Checksum algorithm: xor or concat")
    String algorithm;

    @Option(names = "--bisection-factor", description = "Bisection factor")
    Integer bisectionFactor;

    @Option(names = "--bisection-threshold", description = "Bisection threshold")
    Long bisectionThreshold;

    @Option(names = "--batch-size", description = "Batch size")
    Integer batchSize;

    @Option(names = "--max-differences", description = "Maximum retained differences")
    Long maxDifferences;

    AIConfigRequest toRequest() {
        return AIConfigRequest.builder()
                .goal(goal)
                .sourceType(sourceType)
                .sourceUrl(sourceUrl)
                .sourceName(sourceName)
                .sourceTable(sourceTable)
                .sourceQuery(sourceQuery)
                .sourceUserEnv(sourceUserEnv)
                .sourcePasswordEnv(sourcePasswordEnv)
                .targetType(targetType)
                .targetUrl(targetUrl)
                .targetName(targetName)
                .targetTable(targetTable)
                .targetQuery(targetQuery)
                .targetUserEnv(targetUserEnv)
                .targetPasswordEnv(targetPasswordEnv)
                .keys(keys)
                .sourceKeys(sourceKeys)
                .targetKeys(targetKeys)
                .fields(fields)
                .sourceFields(sourceFields)
                .targetFields(targetFields)
                .strategyMode(strategyMode)
                .algorithm(algorithm)
                .bisectionFactor(bisectionFactor)
                .bisectionThreshold(bisectionThreshold)
                .batchSize(batchSize)
                .maxDifferences(maxDifferences)
                .backendOptions(AIBackendOptions.builder()
                        .backend(backend)
                        .model(model)
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .timeout(timeout)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .noLlm(noLlm)
                        .build())
                .build();
    }
}
