package com.consilens.cli.model;

import com.consilens.cli.model.normalization.NormalizationConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.ComparisonStrategy;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CLI configuration model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CliConfiguration {

    @JsonProperty("source")
    private ConnectionConfig source;

    @JsonProperty("target")
    private ConnectionConfig target;

    @JsonProperty("comparison")
    private ComparisonConfig comparison;

    @JsonProperty("strategy")
    private StrategyConfig strategy;

    @JsonProperty("concurrency")
    private ConcurrencyConfig concurrency;

    @JsonProperty("normalization")
    private NormalizationConfig normalization;

    @JsonProperty("result")
    private ResultConfig result;

    @JsonIgnore
    public ComparisonStrategy getStrategyEnum() {
        return ComparisonStrategy.fromString(getStrategyMode());
    }

    @JsonIgnore
    public ChecksumAlgorithm getAlgorithmEnum() {
        return ChecksumAlgorithm.fromString(getAlgorithm());
    }

    @JsonIgnore
    public String getStrategyMode() {
        return strategy != null ? strategy.getMode() : null;
    }

    @JsonIgnore
    public String getAlgorithm() {
        return strategy != null ? strategy.getAlgorithm() : null;
    }

    @JsonIgnore
    public boolean isValid() {
        try {
            validate();
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    @JsonIgnore
    public String getValidationError() {
        try {
            validate();
            return null;
        } catch (ValidationException e) {
            return e.getMessage();
        }
    }

    public static CliConfiguration createBasicTemplate() {
        SinkConfig consoleSink = new SinkConfig();
        consoleSink.setFormat("console");
        consoleSink.setType("result");

        SinkConfig jsonSink = new SinkConfig();
        jsonSink.setFormat("json");
        jsonSink.setType("diff-record");
        jsonSink.setProperties("{\"path\":\"./diff-results.json\",\"pretty\":true}");

        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .url("jdbc:mysql://localhost:3306/database1")
                        .username("user1")
                        .password("password1")
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .url("jdbc:mysql://localhost:3306/database2")
                        .username("user2")
                        .password("password2")
                        .build())
                .comparison(ComparisonConfig.builder()
                        .tables(StringPairConfig.builder().source("table1").target("table2").build())
                        .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("concat")
                        .batchSize(1000)
                        .bisectionFactor(4)
                        .bisectionThreshold(10000L)
                        .enableProfiling(false)
                        .build())
                .result(ResultConfig.builder()
                        .sinks(List.of(consoleSink, jsonSink))
                        .build())
                .build();
    }

    public static CliConfiguration createAdvancedTemplate() {
        SinkConfig consoleSink = new SinkConfig();
        consoleSink.setFormat("console");
        consoleSink.setType("result");

        SinkConfig tableSink = new SinkConfig();
        tableSink.setFormat("table");
        tableSink.setType("diff-record");
        tableSink.setProperties("{\"url\":\"jdbc:mysql://localhost:3306/consilens_results?useSSL=false&serverTimezone=UTC\","
                + "\"username\":\"consilens_user\","
                + "\"password\":\"consilens_pass\","
                + "\"driver\":\"com.mysql.cj.jdbc.Driver\","
                + "\"maxPoolSize\":10,"
                + "\"prefix\":\"diff_results_\","
                + "\"suffixTimestamp\":true,"
                + "\"createTable\":true,"
                + "\"dropIfExists\":false,"
                + "\"defaultColumnLength\":500,"
                + "\"batchSize\":100}");

        Map<String, TypeNormalizationRule> globalRules = new HashMap<>();
        TypeNormalizationRule decimalRule = new TypeNormalizationRule();
        decimalRule.setPrecision(2);
        decimalRule.setRounding(true);
        TypeNormalizationRule timestampRule = new TypeNormalizationRule();
        timestampRule.setFormat("yyyy-MM-dd HH:mm:ss");
        timestampRule.setTimezone("UTC");
        globalRules.put("decimal", decimalRule);
        globalRules.put("timestamp", timestampRule);

        ConcurrencyConfig concurrencyConfig = new ConcurrencyConfig(
                new ConcurrencyConfig.PoolConfig(8, 32, 10000, 60L, "consilens-io-"),
                new ConcurrencyConfig.PoolConfig(4, 8, 5000, 60L, "consilens-cpu-")
        );

        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .url("jdbc:mysql://localhost:3306/database1?useSSL=false&serverTimezone=UTC")
                        .username("user1")
                        .password("password1")
                        .build())
                .target(ConnectionConfig.builder()
                        .type("postgresql")
                        .url("jdbc:postgresql://localhost:5432/database2?currentSchema=public")
                        .username("user2")
                        .password("password2")
                        .build())
                .comparison(ComparisonConfig.builder()
                        .tables(StringPairConfig.builder().source("users").target("customers").build())
                        .keys(ListPairConfig.builder()
                                .source(List.of("id", "email"))
                                .target(List.of("id", "email"))
                                .build())
                        .compareColumns(ListPairConfig.builder()
                                .source(List.of("name", "email", "phone", "created_at"))
                                .target(List.of("name", "email", "phone", "created_at"))
                                .build())
                        .where(StringPairConfig.builder()
                                .source("created_at >= '2026-01-01'")
                                .target("created_at >= '2026-01-01'")
                                .build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("xor")
                        .batchSize(5000)
                        .bisectionFactor(4)
                        .bisectionThreshold(50000L)
                        .enableProfiling(true)
                        .build())
                .concurrency(concurrencyConfig)
                .normalization(NormalizationConfig.builder()
                        .global(globalRules)
                        .build())
                .result(ResultConfig.builder()
                        .sinks(List.of(consoleSink, tableSink))
                        .build())
                .build();
    }

    public void validate() throws ValidationException {
        ValidationFramework.forContext("CLI配置")
                .validate(source, "source", Objects::nonNull, "source 配置不能为空")
                .validate(target, "target", Objects::nonNull, "target 配置不能为空")
                .validate(comparison, "comparison", Objects::nonNull, "comparison 配置不能为空")
                .validate(strategy, "strategy", Objects::nonNull, "strategy 配置不能为空")
                .throwIfInvalid();

        source.validate("source");
        target.validate("target");
        comparison.validate();
        strategy.validate();

        if (normalization != null) {
            normalization.validate();
        }
    }

    public void validateDatabaseConnections() throws ValidationException {
        ValidationFramework.forContext("数据库连接配置")
                .validate(source, "source", Objects::nonNull, "source 配置不能为空")
                .validate(target, "target", Objects::nonNull, "target 配置不能为空")
                .throwIfInvalid();

        source.validate("source");
        target.validate("target");
    }
}
