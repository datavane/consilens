package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CompareMappingConfig;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.FieldExpressionConfig;
import com.consilens.cli.model.ListPairConfig;
import com.consilens.cli.model.LocalCompareConfig;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.cli.model.StringPairConfig;
import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.connector.api.planner.CompareRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompareRequestFactoryTest {

    private final CompareRequestFactory factory = new CompareRequestFactory();

    @Test
    void shouldCompileMappingsToSqlResources() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("order_id")).build())
                .mappings(List.of(
                        CompareMappingConfig.builder()
                                .name("order_amount")
                                .source(FieldExpressionConfig.builder().column("amount").build())
                                .target(FieldExpressionConfig.builder().expression("actual_amount + discount_amount").build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("order_status")
                                .source(FieldExpressionConfig.builder().column("status").build())
                                .target(FieldExpressionConfig.builder()
                                        .expression("CASE WHEN state = 1 THEN 'PAID' ELSE 'UNPAID' END")
                                        .build())
                                .build()))
                .exclude(ListPairConfig.builder()
                        .source(List.of("order_status"))
                        .target(List.of("order_status"))
                        .build())
                .filters(StringPairConfig.builder().source("status IS NOT NULL").target("state IS NOT NULL").build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals("sql", request.getSource().getResource().getType());
        assertEquals("sql", request.getTarget().getResource().getType());
        assertEquals(List.of("id"), request.getSourceKeySpec().getFields());
        assertEquals(List.of("id"), request.getTargetKeySpec().getFields());
        assertEquals(List.of("order_amount"), request.getSourceComparisons().getFields());
        assertEquals(List.of("order_status"), request.getSourceComparisons().getExclude());
        assertEquals(List.of("order_status"), request.getTargetComparisons().getExclude());
        assertEquals("SELECT id, amount AS order_amount FROM source_table WHERE status IS NOT NULL",
                request.getSource().getResource().getPath());
        assertEquals("SELECT order_id AS id, actual_amount + discount_amount AS order_amount FROM target_table WHERE state IS NOT NULL",
                request.getTarget().getResource().getPath());
        assertEquals(null, request.getSourceFilter());
        assertEquals(null, request.getTargetFilter());
        assertEquals(Boolean.TRUE, request.getExecutionOptions().getValidateUniqueKeys());
        assertEquals(1_000_000L, request.getExecutionOptions().getMaxDifferences());
    }

    @Test
    void shouldTreatCustomSqlTablesAsSqlResources() {
        CliConfiguration config = baseConfig();
        config.getSource().setResource(ConnectionConfig.ResourceConfig.builder()
                .type("sql")
                .path("SELECT id, amount AS order_amount FROM order_detail")
                .build());
        config.getTarget().setResource(ConnectionConfig.ResourceConfig.builder()
                .type("sql")
                .path("WITH base AS (SELECT id, actual_amount AS order_amount FROM order_summary) SELECT id, order_amount FROM base")
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder()
                        .source(List.of("order_amount", "source_updated_at"))
                        .target(List.of("order_amount", "target_updated_at"))
                        .build())
                .exclude(ListPairConfig.builder()
                        .source(List.of("source_updated_at"))
                        .target(List.of("target_updated_at"))
                        .build())
                .filters(StringPairConfig.builder().source("id > 10").target("id > 10").build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals("sql", request.getSource().getResource().getType());
        assertEquals("sql", request.getTarget().getResource().getType());
        assertEquals(List.of("order_amount", "source_updated_at"), request.getSourceComparisons().getFields());
        assertEquals(List.of("source_updated_at"), request.getSourceComparisons().getExclude());
        assertEquals(List.of("target_updated_at"), request.getTargetComparisons().getExclude());
        assertEquals("id > 10", request.getSourceFilter().getExpression());
    }

    @Test
    void shouldMapChecksumStrategyToPreferredPlansAndExecutionOptions() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("xor")
                .bisectionFactor(8)
                .bisectionThreshold(12_000L)
                .batchSize(2000)
                .enableProfiling(true)
                .maxDifferences(123L)
                .localCompare(LocalCompareConfig.builder().mode("row-hash").build())
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("order_id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(List.of(
                        ComparePlanTypes.PUSHDOWN_CHECKSUM,
                        ComparePlanTypes.KEY_HASH,
                        ComparePlanTypes.STREAMING_MERGE),
                request.getStrategyPreference().getPreferredPlans());
        assertEquals(Boolean.TRUE, request.getStrategyPreference().getAllowFallback());
        assertEquals("xor", request.getExecutionOptions().getChecksumAlgorithm());
        assertEquals(8, request.getExecutionOptions().getBisectionFactor());
        assertEquals(12_000L, request.getExecutionOptions().getBisectionThreshold());
        assertEquals(Boolean.TRUE, request.getExecutionOptions().getEnableProfiling());
        assertEquals("row-hash", request.getExecutionOptions().getLocalCompareMode());
        assertEquals(Boolean.TRUE, request.getExecutionOptions().getValidateUniqueKeys());
        assertEquals(123L, request.getExecutionOptions().getMaxDifferences());
        assertEquals(123L, request.getExecutionOptions().getAttributes().get("maxDifferences"));
    }

    @Test
    void shouldMapJoinStrategyToRequiredServerJoinPlan() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("join")
                .algorithm("concat")
                .bisectionFactor(4)
                .batchSize(1000)
                .maxDifferences(1000L)
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(List.of(ComparePlanTypes.SERVER_JOIN), request.getStrategyPreference().getPreferredPlans());
        assertEquals(Boolean.FALSE, request.getStrategyPreference().getAllowFallback());
    }

    @Test
    void shouldUseBatchSizeWhenBisectionThresholdIsNotConfigured() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(4)
                .batchSize(321)
                .maxDifferences(1000L)
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(3210L, request.getExecutionOptions().getBisectionThreshold());
    }

    @Test
    void shouldCompileMappingKeysLiteralsAndDisabledCompareFields() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("order_id")).target(List.of("id")).build())
                .mappings(List.of(
                        CompareMappingConfig.builder()
                                .name("logical_id")
                                .key(true)
                                .source(FieldExpressionConfig.builder().column("order_id").build())
                                .target(FieldExpressionConfig.builder().column("id").build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("source_name")
                                .source(FieldExpressionConfig.builder().column("name").build())
                                .target(FieldExpressionConfig.builder().column("full_name").build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("constant_flag")
                                .source(FieldExpressionConfig.builder().literal(true).build())
                                .target(FieldExpressionConfig.builder().literal(false).build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("debug_only")
                                .compare(false)
                                .source(FieldExpressionConfig.builder().column("debug_source").build())
                                .target(FieldExpressionConfig.builder().column("debug_target").build())
                                .build()))
                .exclude(ListPairConfig.builder()
                        .source(List.of("constant_flag"))
                        .target(List.of("constant_flag"))
                        .build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(List.of("logical_id"), request.getSourceKeySpec().getFields());
        assertEquals(List.of("source_name"), request.getSourceComparisons().getFields());
        assertEquals(List.of("constant_flag"), request.getSourceComparisons().getExclude());
        assertEquals("SELECT order_id AS logical_id, name AS source_name, debug_source AS debug_only FROM source_table",
                request.getSource().getResource().getPath());
        assertEquals("SELECT id AS logical_id, full_name AS source_name, debug_target AS debug_only FROM target_table",
                request.getTarget().getResource().getPath());
    }

    private CliConfiguration baseConfig() {
        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/source")
                                .username("root")
                                .password("secret")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("source_table").build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/target")
                                .username("root")
                                .password("secret")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("target_table").build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("xor")
                        .bisectionFactor(4)
                        .bisectionThreshold(1000L)
                        .maxDifferences(1_000_000L)
                        .localCompare(LocalCompareConfig.builder().mode("full").build())
                        .build())
                .build();
    }
}
