package com.consilens.cli.model;

import com.consilens.cli.model.normalization.NormalizationConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.ComparisonStrategy;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialects;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.consilens.sink.table.TableColumnNames;
import com.consilens.sink.table.TableSinkConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CLI configuration model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class CliConfiguration {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                        .name("source-mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/database1")
                                .username("user1")
                                .password("password1")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder()
                                .type("table")
                                .name("table1")
                                .build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .name("target-mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/database2")
                                .username("user2")
                                .password("password2")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder()
                                .type("table")
                                .name("table2")
                                .build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("concat")
                        .batchSize(1000)
                        .bisectionFactor(4)
                        .bisectionThreshold(10000L)
                        .enableProfiling(false)
                        .localCompare(LocalCompareConfig.builder().mode("full").build())
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
        tableSink.setProperties("{\"type\":\"mysql\","
                + "\"url\":\"jdbc:mysql://localhost:3306/consilens_results?useSSL=false&serverTimezone=UTC\","
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
                        .name("source-mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/database1?useSSL=false&serverTimezone=UTC")
                                .username("user1")
                                .password("password1")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder()
                                .type("table")
                                .name("users")
                                .build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("postgresql")
                        .name("target-postgresql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:postgresql://localhost:5432/database2?currentSchema=public")
                                .username("user2")
                                .password("password2")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder()
                                .type("table")
                                .name("customers")
                                .build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .keys(ListPairConfig.builder()
                                .source(List.of("id", "email"))
                                .target(List.of("id", "email"))
                                .build())
                        .fields(ListPairConfig.builder()
                                .source(List.of("name", "email", "phone", "created_at"))
                                .target(List.of("name", "email", "phone", "created_at"))
                                .build())
                        .filters(StringPairConfig.builder()
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
                        .localCompare(LocalCompareConfig.builder().mode("full").build())
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
        validateResultSinks();
    }

    public void validateDatabaseConnections() throws ValidationException {
        ValidationFramework.forContext("数据库连接配置")
                .validate(source, "source", Objects::nonNull, "source 配置不能为空")
                .validate(target, "target", Objects::nonNull, "target 配置不能为空")
                .throwIfInvalid();

        source.validate("source");
        target.validate("target");
    }

    private void validateResultSinks() {
        if (result == null || result.getSinks() == null) {
            return;
        }
        for (int i = 0; i < result.getSinks().size(); i++) {
            SinkConfig sink = result.getSinks().get(i);
            if (sink == null || !"table".equalsIgnoreCase(sink.getFormat())) {
                continue;
            }
            TableSinkConfig tableSinkConfig = parseTableSinkConfig(i, sink);
            String databaseType;
            try {
                databaseType = tableSinkConfig.resolveDatabaseType();
            } catch (IllegalStateException e) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "result.sinks[" + i + "].properties.type is required for table sinks");
            }
            if (!"mysql".equals(databaseType) && !"postgresql".equals(databaseType)) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "result.sinks[" + i + "].properties.type must be mysql or postgresql");
            }
            DatabaseDialect dialect = DatabaseDialects.require(databaseType);
            dialect.getTableWriteCompiler();
            validateTableSinkColumns(i, tableSinkConfig, dialect);
        }
    }

    private void validateTableSinkColumns(int sinkIndex, TableSinkConfig tableSinkConfig, DatabaseDialect dialect) {
        if (tableSinkConfig.getColumns() == null || tableSinkConfig.getColumns().isEmpty()) {
            return;
        }
        try {
            TableColumnNames.validateUniqueSanitizedColumns(tableSinkConfig.getColumns(),
                    "result.sinks[" + sinkIndex + "]");
        } catch (IllegalArgumentException e) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", e.getMessage());
        }
        Set<String> columnNames = new HashSet<>();
        for (int i = 0; i < tableSinkConfig.getColumns().size(); i++) {
            String columnName = tableSinkConfig.getColumns().get(i).getName();
            if (columnName == null || columnName.isBlank()) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "result.sinks[" + sinkIndex + "].columns[" + i + "].name 不能为空");
            }
            if (!columnNames.add(columnName.trim().toLowerCase())) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "result.sinks[" + sinkIndex + "] contains duplicate column name: " + columnName);
            }
            String declaredColumnType = tableSinkConfig.getColumns().get(i).getColumnType();
            if (declaredColumnType == null || declaredColumnType.isBlank()) {
                continue;
            }
            if (dialect.getDataTypeHandler().convertToTypeDescriptor(extractTypeDefinition(declaredColumnType)).getType()
                    == com.consilens.common.enums.DataType.UNKNOWN_TYPE) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        "result.sinks[" + sinkIndex + "].columns[" + i + "].columnType: target connector "
                                + databaseTypeName(dialect) + " cannot parse type definition \"" + declaredColumnType + "\"");
            }
        }
    }

    private String databaseTypeName(DatabaseDialect dialect) {
        return dialect != null ? dialect.getConnectorType() : "unknown";
    }

    private String extractTypeDefinition(String declaredColumnType) {
        String normalized = declaredColumnType.trim();
        String upper = normalized.toUpperCase();
        for (String keyword : List.of(" PRIMARY KEY", " NOT NULL", " NULL", " UNIQUE", " DEFAULT ", " CHECK ", " REFERENCES ")) {
            int index = upper.indexOf(keyword);
            if (index >= 0) {
                return normalized.substring(0, index).trim();
            }
        }
        return normalized;
    }

    private TableSinkConfig parseTableSinkConfig(int index, SinkConfig sink) {
        try {
            return OBJECT_MAPPER.readValue(sink.getProperties(), TableSinkConfig.class);
        } catch (Exception e) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    "result.sinks[" + index + "] table sink properties are invalid: " + e.getMessage());
        }
    }
}
