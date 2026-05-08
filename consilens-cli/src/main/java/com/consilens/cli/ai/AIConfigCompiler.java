package com.consilens.cli.ai;

import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;
import com.consilens.ai.config.model.ResultDraft;
import com.consilens.ai.config.model.StrategyDraft;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.ListPairConfig;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a validated AI draft into the canonical CLI configuration model.
 */
public class AIConfigCompiler {

    private final ObjectMapper yamlMapper;

    public AIConfigCompiler() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT);
        this.yamlMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    public CliConfiguration compile(AIConfigDraft draft) {
        MappingDraft mapping = draft.getMapping();
        StrategyDraft strategy = draft.getStrategy() == null ? StrategyDraft.builder().build() : draft.getStrategy();

        return CliConfiguration.builder()
                .source(connection(draft.getSource(), "source"))
                .target(connection(draft.getTarget(), "target"))
                .comparison(ComparisonConfig.builder()
                        .keys(ListPairConfig.builder()
                                .source(copy(mapping.getSourceKeys()))
                                .target(copy(mapping.getTargetKeys()))
                                .build())
                        .fields(fields(mapping))
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode(defaultValue(strategy.getMode(), "checksum"))
                        .algorithm(defaultValue(strategy.getAlgorithm(), "xor"))
                        .bisectionFactor(defaultValue(strategy.getBisectionFactor(), 4))
                        .bisectionThreshold(strategy.getBisectionThreshold())
                        .batchSize(defaultValue(strategy.getBatchSize(), 1000))
                        .maxDifferences(defaultValue(strategy.getMaxDifferences(), 1_000_000L))
                        .build())
                .result(result(draft.getResult()))
                .build();
    }

    public String toYaml(CliConfiguration configuration) {
        try {
            return yamlMapper.writeValueAsString(configuration);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize AI generated configuration", e);
        }
    }

    private ConnectionConfig connection(DatasetDraft dataset, String side) {
        String resourceType = defaultValue(dataset.getResourceType(), dataset.getQuery() == null ? "table" : "sql");
        ConnectionConfig.ResourceConfig resource = ConnectionConfig.ResourceConfig.builder()
                .type(resourceType)
                .name("table".equalsIgnoreCase(resourceType) ? dataset.getResourceName() : null)
                .path("sql".equalsIgnoreCase(resourceType) ? dataset.getQuery() : null)
                .build();
        return ConnectionConfig.builder()
                .type(dataset.getType())
                .name(defaultValue(dataset.getName(), side + "-" + dataset.getType()))
                .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                        .url(dataset.getJdbcUrl())
                        .username(env(dataset.getUsernameEnv()))
                        .password(env(dataset.getPasswordEnv()))
                        .build())
                .resource(resource)
                .build();
    }

    private ListPairConfig fields(MappingDraft mapping) {
        if (mapping.getSourceFields() == null || mapping.getSourceFields().isEmpty()
                || mapping.getTargetFields() == null || mapping.getTargetFields().isEmpty()) {
            return null;
        }
        return ListPairConfig.builder()
                .source(copy(mapping.getSourceFields()))
                .target(copy(mapping.getTargetFields()))
                .build();
    }

    private ResultConfig result(ResultDraft draft) {
        SinkConfig sink = new SinkConfig();
        sink.setFormat(draft == null ? "console" : defaultValue(draft.getSinkFormat(), "console"));
        sink.setType(draft == null ? "result" : defaultValue(draft.getSinkType(), "result"));
        return ResultConfig.builder()
                .sinks(List.of(sink))
                .build();
    }

    private String env(String name) {
        return "${env." + name + "}";
    }

    private List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private Integer defaultValue(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private Long defaultValue(Long value, Long fallback) {
        return value == null ? fallback : value;
    }
}
