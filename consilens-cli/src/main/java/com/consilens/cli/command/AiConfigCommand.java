package com.consilens.cli.command;

import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.cli.ai.AIConfigRequest;
import com.consilens.cli.ai.AIConfigResult;
import com.consilens.cli.ai.AIConfigService;
import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.DiffService;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Generates a production-shaped Consilens YAML configuration with AI assistance.
 */
@Slf4j
@Command(
    name = "config",
    description = "Generate a validated Consilens YAML configuration from a goal and explicit hints",
    mixinStandardHelpOptions = true
)
public class AiConfigCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Natural language diff goal")
    private String goal;

    @Option(names = "--backend", defaultValue = "noop", description = "AI backend: noop, ollama, openai, deepseek")
    private String backend;

    @Option(names = "--model", description = "AI model name")
    private String model;

    @Option(names = "--base-url", description = "AI backend base URL")
    private String baseUrl;

    @Option(names = "--api-key", description = "AI backend API key")
    private String apiKey;

    @Option(names = "--timeout", description = "AI backend timeout")
    private String timeout;

    @Option(names = "--temperature", description = "AI sampling temperature")
    private Double temperature;

    @Option(names = "--max-tokens", description = "AI max output tokens")
    private Integer maxTokens;

    @Option(names = "--no-llm", description = "Do not call an LLM; only use explicit CLI hints")
    private boolean noLlm;

    @Option(names = "--source-type", description = "Source connector type")
    private String sourceType;

    @Option(names = "--source-url", description = "Source JDBC URL")
    private String sourceUrl;

    @Option(names = "--source-name", description = "Source logical name")
    private String sourceName;

    @Option(names = "--source-table", description = "Source table name")
    private String sourceTable;

    @Option(names = "--source-query", description = "Source SELECT/WITH query")
    private String sourceQuery;

    @Option(names = "--source-user-env", defaultValue = "SOURCE_USERNAME", description = "Source username env var name")
    private String sourceUserEnv;

    @Option(names = "--source-password-env", defaultValue = "SOURCE_PASSWORD", description = "Source password env var name")
    private String sourcePasswordEnv;

    @Option(names = "--target-type", description = "Target connector type")
    private String targetType;

    @Option(names = "--target-url", description = "Target JDBC URL")
    private String targetUrl;

    @Option(names = "--target-name", description = "Target logical name")
    private String targetName;

    @Option(names = "--target-table", description = "Target table name")
    private String targetTable;

    @Option(names = "--target-query", description = "Target SELECT/WITH query")
    private String targetQuery;

    @Option(names = "--target-user-env", defaultValue = "TARGET_USERNAME", description = "Target username env var name")
    private String targetUserEnv;

    @Option(names = "--target-password-env", defaultValue = "TARGET_PASSWORD", description = "Target password env var name")
    private String targetPasswordEnv;

    @Option(names = "--keys", description = "Comma-separated key columns used on both sides")
    private String keys;

    @Option(names = "--source-keys", description = "Comma-separated source key columns")
    private String sourceKeys;

    @Option(names = "--target-keys", description = "Comma-separated target key columns")
    private String targetKeys;

    @Option(names = "--fields", description = "Comma-separated fields used on both sides")
    private String fields;

    @Option(names = "--source-fields", description = "Comma-separated source fields")
    private String sourceFields;

    @Option(names = "--target-fields", description = "Comma-separated target fields")
    private String targetFields;

    @Option(names = "--strategy-mode", defaultValue = "checksum", description = "Strategy mode: checksum or join")
    private String strategyMode;

    @Option(names = "--algorithm", defaultValue = "xor", description = "Checksum algorithm: xor or concat")
    private String algorithm;

    @Option(names = "--bisection-factor", description = "Bisection factor")
    private Integer bisectionFactor;

    @Option(names = "--bisection-threshold", description = "Bisection threshold")
    private Long bisectionThreshold;

    @Option(names = "--batch-size", description = "Batch size")
    private Integer batchSize;

    @Option(names = "--max-differences", description = "Maximum retained differences")
    private Long maxDifferences;

    @Option(names = {"-o", "--output"}, description = "Output YAML file")
    private String output;

    @Option(names = "--dry-run", description = "Run DiffService dry-run after generating the config")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            AIConfigResult result = new AIConfigService().generate(request());
            if (!result.isValid()) {
                printIssues(result);
                System.err.println("No file was written.");
                return 1;
            }

            if (dryRun) {
                new DiffService().performDryRun(result.getConfiguration());
            }

            if (output == null || output.isBlank()) {
                System.out.println(result.getYaml());
            } else {
                Path outputPath = Path.of(output).toAbsolutePath().normalize();
                if (outputPath.getParent() != null) {
                    Files.createDirectories(outputPath.getParent());
                }
                Files.writeString(outputPath, result.getYaml());
                System.out.println("[AI CONFIG] generated=" + outputPath);
            }
            System.out.println("[AI CONFIG] validation=passed");
            if (dryRun) {
                System.out.println("[AI CONFIG] dryRun=passed");
            }
            return 0;
        } catch (Exception e) {
            log.error("AI config generation failed", e);
            System.err.println("[AI CONFIG ERROR] " + e.getMessage());
            return 1;
        }
    }

    private AIConfigRequest request() {
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

    private void printIssues(AIConfigResult result) {
        System.err.println("[AI CONFIG ERROR] validation failed");
        if (result.getIssues() == null) {
            return;
        }
        for (AIConfigIssue issue : result.getIssues()) {
            System.err.printf("%s %s %s:%n  %s%n",
                    issue.getSeverity(), issue.getCode(), issue.getPath(), issue.getMessage());
        }
    }
}
