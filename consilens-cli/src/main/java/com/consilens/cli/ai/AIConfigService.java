package com.consilens.cli.ai;

import com.consilens.ai.config.AIConfigDraftValidator;
import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;
import com.consilens.ai.config.model.ResultDraft;
import com.consilens.ai.config.model.StrategyDraft;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates a validated Consilens CLI configuration from explicit hints and optional LLM output.
 */
public class AIConfigService {

    private final AIConfigDraftValidator validator;
    private final AIConfigCompiler compiler;
    private final LLMBackendResolver backendResolver;
    private final ObjectMapper objectMapper;

    public AIConfigService() {
        this(new AIConfigDraftValidator(), new AIConfigCompiler(), new LLMBackendResolver(), new ObjectMapper());
    }

    AIConfigService(AIConfigDraftValidator validator,
                    AIConfigCompiler compiler,
                    LLMBackendResolver backendResolver,
                    ObjectMapper objectMapper) {
        this.validator = validator;
        this.compiler = compiler;
        this.backendResolver = backendResolver;
        this.objectMapper = objectMapper;
    }

    public AIConfigResult generate(AIConfigRequest request) {
        AIConfigDraft draft = buildDraftFromRequest(request);
        List<AIConfigIssue> issues = validator.validate(draft);

        AIBackendOptions backendOptions = request.getBackendOptions();
        String backendName = backendResolver.resolveBackendName(backendOptions);
        if (validator.hasErrors(issues)
                && backendOptions != null
                && !backendOptions.isNoLlm()
                && !"noop".equalsIgnoreCase(backendName)) {
            draft = mergeExplicitHints(request, generateDraftWithLlm(request));
            issues = validator.validate(draft);
        }

        if (validator.hasErrors(issues)) {
            return AIConfigResult.builder()
                    .draft(draft)
                    .issues(issues)
                    .valid(false)
                    .dryRunPassed(false)
                    .build();
        }

        CliConfiguration configuration = compiler.compile(draft);
        try {
            configuration.validate();
        } catch (Exception e) {
            issues = List.of(AIConfigIssue.builder()
                    .severity(AIConfigIssue.Severity.ERROR)
                    .path("configuration")
                    .code("AI_CONFIG_COMPILED_CONFIG_INVALID")
                    .message(e.getMessage())
                    .build());
            return AIConfigResult.builder()
                    .draft(draft)
                    .configuration(configuration)
                    .issues(issues)
                    .valid(false)
                    .dryRunPassed(false)
                    .build();
        }

        return AIConfigResult.builder()
                .draft(draft)
                .configuration(configuration)
                .yaml(compiler.toYaml(configuration))
                .issues(issues)
                .valid(true)
                .dryRunPassed(false)
                .build();
    }

    private AIConfigDraft generateDraftWithLlm(AIConfigRequest request) {
        LLMBackend backend = backendResolver.resolve(request.getBackendOptions());
        String response = backend.complete(buildPrompt(request));
        try {
            return objectMapper.readValue(extractJson(response), AIConfigDraft.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI backend did not return a valid AIConfigDraft JSON: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(AIConfigRequest request) {
        return "Generate a Consilens AIConfigDraft JSON only.\n"
                + "Do not output markdown.\n"
                + "Never include plaintext passwords. Use usernameEnv and passwordEnv.\n"
                + "Required JSON shape:\n"
                + "{\n"
                + "  \"source\": {\"type\":\"\",\"jdbcUrl\":\"\",\"usernameEnv\":\"\",\"passwordEnv\":\"\","
                + "\"resourceType\":\"table\",\"resourceName\":\"\"},\n"
                + "  \"target\": {\"type\":\"\",\"jdbcUrl\":\"\",\"usernameEnv\":\"\",\"passwordEnv\":\"\","
                + "\"resourceType\":\"table\",\"resourceName\":\"\"},\n"
                + "  \"mapping\": {\"sourceKeys\":[],\"targetKeys\":[],\"sourceFields\":[],\"targetFields\":[]},\n"
                + "  \"strategy\": {\"mode\":\"checksum\",\"algorithm\":\"xor\"},\n"
                + "  \"result\": {\"sinkFormat\":\"console\",\"sinkType\":\"result\"},\n"
                + "  \"assumptions\": [],\n"
                + "  \"warnings\": []\n"
                + "}\n"
                + "The compiler will also add a json diff-record evidence sink for diagnostics.\n"
                + "User goal:\n"
                + nullToEmpty(request.getGoal());
    }

    private String extractJson(String response) {
        if (response == null) {
            return "";
        }
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private AIConfigDraft mergeExplicitHints(AIConfigRequest request, AIConfigDraft draft) {
        AIConfigDraft explicit = buildDraftFromRequest(request);
        if (draft == null) {
            return explicit;
        }
        draft.setSource(mergeDataset(explicit.getSource(), draft.getSource()));
        draft.setTarget(mergeDataset(explicit.getTarget(), draft.getTarget()));
        draft.setMapping(mergeMapping(explicit.getMapping(), draft.getMapping()));
        draft.setStrategy(mergeStrategy(explicit.getStrategy(), draft.getStrategy()));
        if (draft.getResult() == null) {
            draft.setResult(explicit.getResult());
        }
        return draft;
    }

    private DatasetDraft mergeDataset(DatasetDraft explicit, DatasetDraft generated) {
        if (generated == null) {
            return explicit;
        }
        generated.setType(first(explicit.getType(), generated.getType()));
        generated.setName(first(explicit.getName(), generated.getName()));
        generated.setJdbcUrl(first(explicit.getJdbcUrl(), generated.getJdbcUrl()));
        generated.setUsernameEnv(first(explicit.getUsernameEnv(), generated.getUsernameEnv()));
        generated.setPasswordEnv(first(explicit.getPasswordEnv(), generated.getPasswordEnv()));
        generated.setResourceType(first(explicit.getResourceType(), generated.getResourceType()));
        generated.setResourceName(first(explicit.getResourceName(), generated.getResourceName()));
        generated.setQuery(first(explicit.getQuery(), generated.getQuery()));
        return generated;
    }

    private MappingDraft mergeMapping(MappingDraft explicit, MappingDraft generated) {
        if (generated == null) {
            return explicit;
        }
        if (explicit.getSourceKeys() != null && !explicit.getSourceKeys().isEmpty()) {
            generated.setSourceKeys(explicit.getSourceKeys());
        }
        if (explicit.getTargetKeys() != null && !explicit.getTargetKeys().isEmpty()) {
            generated.setTargetKeys(explicit.getTargetKeys());
        }
        if (explicit.getSourceFields() != null && !explicit.getSourceFields().isEmpty()) {
            generated.setSourceFields(explicit.getSourceFields());
        }
        if (explicit.getTargetFields() != null && !explicit.getTargetFields().isEmpty()) {
            generated.setTargetFields(explicit.getTargetFields());
        }
        return generated;
    }

    private StrategyDraft mergeStrategy(StrategyDraft explicit, StrategyDraft generated) {
        if (generated == null) {
            return explicit;
        }
        generated.setMode(first(explicit.getMode(), generated.getMode()));
        generated.setAlgorithm(first(explicit.getAlgorithm(), generated.getAlgorithm()));
        generated.setBisectionFactor(explicit.getBisectionFactor() != null
                ? explicit.getBisectionFactor() : generated.getBisectionFactor());
        generated.setBisectionThreshold(explicit.getBisectionThreshold() != null
                ? explicit.getBisectionThreshold() : generated.getBisectionThreshold());
        generated.setBatchSize(explicit.getBatchSize() != null ? explicit.getBatchSize() : generated.getBatchSize());
        generated.setMaxDifferences(explicit.getMaxDifferences() != null
                ? explicit.getMaxDifferences() : generated.getMaxDifferences());
        return generated;
    }

    private AIConfigDraft buildDraftFromRequest(AIConfigRequest request) {
        List<String> sourceKeys = list(first(request.getSourceKeys(), request.getKeys()));
        List<String> targetKeys = list(first(request.getTargetKeys(), request.getKeys()));
        List<String> sourceFields = list(first(request.getSourceFields(), request.getFields()));
        List<String> targetFields = list(first(request.getTargetFields(), request.getFields()));

        return AIConfigDraft.builder()
                .source(DatasetDraft.builder()
                        .type(request.getSourceType())
                        .name(request.getSourceName())
                        .jdbcUrl(request.getSourceUrl())
                        .usernameEnv(defaultEnv(request.getSourceUserEnv(), "SOURCE_USERNAME"))
                        .passwordEnv(defaultEnv(request.getSourcePasswordEnv(), "SOURCE_PASSWORD"))
                        .resourceType(request.getSourceQuery() == null ? "table" : "sql")
                        .resourceName(request.getSourceTable())
                        .query(request.getSourceQuery())
                        .build())
                .target(DatasetDraft.builder()
                        .type(request.getTargetType())
                        .name(request.getTargetName())
                        .jdbcUrl(request.getTargetUrl())
                        .usernameEnv(defaultEnv(request.getTargetUserEnv(), "TARGET_USERNAME"))
                        .passwordEnv(defaultEnv(request.getTargetPasswordEnv(), "TARGET_PASSWORD"))
                        .resourceType(request.getTargetQuery() == null ? "table" : "sql")
                        .resourceName(request.getTargetTable())
                        .query(request.getTargetQuery())
                        .build())
                .mapping(MappingDraft.builder()
                        .sourceKeys(sourceKeys)
                        .targetKeys(targetKeys)
                        .sourceFields(sourceFields)
                        .targetFields(targetFields)
                        .build())
                .strategy(StrategyDraft.builder()
                        .mode(request.getStrategyMode())
                        .algorithm(request.getAlgorithm())
                        .bisectionFactor(request.getBisectionFactor())
                        .bisectionThreshold(request.getBisectionThreshold())
                        .batchSize(request.getBatchSize())
                        .maxDifferences(request.getMaxDifferences())
                        .build())
                .result(ResultDraft.builder().sinkFormat("console").sinkType("result").build())
                .build();
    }

    private String defaultEnv(String value, String fallback) {
        return first(value, fallback);
    }

    private List<String> list(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private String first(String explicit, String fallback) {
        return explicit == null || explicit.trim().isEmpty() ? fallback : explicit.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
