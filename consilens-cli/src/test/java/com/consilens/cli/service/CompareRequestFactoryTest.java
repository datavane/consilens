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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // CLI 走开放档：重复键按多重集语义比较（严格档需显式开启）
        assertEquals(Boolean.FALSE, request.getExecutionOptions().getValidateUniqueKeys());
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
    void shouldHonorStrictUniqueKeyValidationFromStrategyConfig() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(4)
                .bisectionThreshold(1000L)
                .batchSize(100)
                .maxDifferences(10L)
                .localCompare(LocalCompareConfig.builder().mode("full").build())
                .validateUniqueKeys(true)
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        // 严格档（命中重复键即失败）由 strategy.validateUniqueKeys 显式打开
        assertEquals(Boolean.TRUE, request.getExecutionOptions().getValidateUniqueKeys());
    }

    @Test
    void shouldDefaultToOpenTierWhenStrategyOmitsValidateUniqueKeys() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(4)
                .bisectionThreshold(1000L)
                .batchSize(100)
                .maxDifferences(10L)
                .localCompare(LocalCompareConfig.builder().mode("full").build())
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        // 默认开放档：重复键按多重集语义比较
        assertEquals(Boolean.FALSE, request.getExecutionOptions().getValidateUniqueKeys());
    }

    @Test
    void shouldHonorAllowNullableKeysFromStrategyConfig() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(4)
                .bisectionThreshold(1000L)
                .batchSize(100)
                .maxDifferences(10L)
                .localCompare(LocalCompareConfig.builder().mode("full").build())
                .allowNullableKeys(true)
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(Boolean.TRUE, request.getExecutionOptions().getAllowNullableKeys());
    }

    @Test
    void shouldDefaultToStrictNullableKeyPolicyWhenStrategyOmitsIt() {
        CliConfiguration config = baseConfig();
        config.setStrategy(StrategyConfig.builder()
                .mode("checksum")
                .algorithm("concat")
                .bisectionFactor(4)
                .bisectionThreshold(1000L)
                .batchSize(100)
                .maxDifferences(10L)
                .localCompare(LocalCompareConfig.builder().mode("full").build())
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(Boolean.FALSE, request.getExecutionOptions().getAllowNullableKeys());
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
                .maxDifferences(123L)
                .localCompare(LocalCompareConfig.builder().mode("row-hash").build())
                .build());
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("order_id")).build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(List.of(
                        ComparePlanTypes.PUSHDOWN_CHECKSUM,
                        ComparePlanTypes.STREAMING_MERGE),
                request.getStrategyPreference().getPreferredPlans());
        assertEquals(Boolean.TRUE, request.getStrategyPreference().getAllowFallback());
        assertEquals("xor", request.getExecutionOptions().getChecksumAlgorithm());
        assertEquals(8, request.getExecutionOptions().getBisectionFactor());
        assertEquals(12_000L, request.getExecutionOptions().getBisectionThreshold());
        assertEquals("row-hash", request.getExecutionOptions().getLocalCompareMode());
        // CLI 走开放档：重复键按多重集语义比较（严格档需显式开启）
        assertEquals(Boolean.FALSE, request.getExecutionOptions().getValidateUniqueKeys());
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
    void shouldUseSideSpecificKeyNamesInLogicalColumns() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder()
                        .source(List.of("user_id"))
                        .target(List.of("uid"))
                        .build())
                .fields(ListPairConfig.builder()
                        .source(List.of("name"))
                        .target(List.of("full_name"))
                        .build())
                .build());

        // 目标侧的逻辑列必须用 target 键名，否则生成的目标 SQL 会引用不存在的列
        assertEquals(List.of("uid", "full_name"), factory.targetColumns(config));
        assertEquals(List.of("user_id", "name"), factory.sourceColumns(config));
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

    @Test
    void shouldUseKeyMappingExpressionAndAllowOmittedKeysSection() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .mappings(List.of(
                        CompareMappingConfig.builder()
                                .name("logical_id")
                                .key(true)
                                .source(FieldExpressionConfig.builder()
                                        .expression("UPPER(order_id)").build())
                                .target(FieldExpressionConfig.builder().column("id").build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("amount")
                                .source(FieldExpressionConfig.builder().column("amount").build())
                                .target(FieldExpressionConfig.builder().column("amount").build())
                                .build()))
                .build());

        CompareRequest request = factory.create(config);

        assertEquals(List.of("logical_id"), request.getSourceKeySpec().getFields());
        assertEquals(List.of("logical_id"), request.getTargetKeySpec().getFields());
        assertEquals("SELECT UPPER(order_id) AS logical_id, amount FROM source_table",
                request.getSource().getResource().getPath());
        assertEquals("SELECT id AS logical_id, amount FROM target_table",
                request.getTarget().getResource().getPath());
    }

    @Test
    void shouldCarrySessionTimeZoneFromTypeRulesIntoNormalizationSpec() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of("amount")).build())
                .build());
        com.consilens.cli.model.normalization.TypeNormalizationRule rule =
                new com.consilens.cli.model.normalization.TypeNormalizationRule();
        rule.setSessionTimeZone("+08:00");
        config.setNormalization(com.consilens.cli.model.normalization.NormalizationConfig.builder()
                .global(java.util.Map.of("datetime", rule))
                .build());

        CompareRequest request = factory.create(config);

        // 连接级时区只能挂在类型规则上，必须一路传到 spec，否则两侧会按不同时区归一化
        assertNotNull(request.getNormalizationSpec());
        assertTrue(request.getNormalizationSpec().getGlobal().stream()
                        .anyMatch(each -> "+08:00".equals(each.getParams().get("sessionTimeZone"))),
                "sessionTimeZone must survive the CLI type-rule -> spec conversion");
    }

    @Test
    void shouldDefaultWithinToleranceToIgnoreWhenNotConfigured() {
        CompareRequest request = factory.create(configWithComparison());

        assertEquals("ignore", request.getExecutionOptions().getWithinTolerance(),
                "没配 tolerance 策略时默认口径是 ignore（容差内不算差异）");
        assertEquals(100, request.getExecutionOptions().getWithinToleranceSamples());
    }

    @Test
    void shouldCarryTolerancePolicyIntoExecutionOptions() {
        CliConfiguration config = configWithComparison();
        config.setNormalization(com.consilens.cli.model.normalization.NormalizationConfig.builder()
                .tolerance(com.consilens.cli.model.normalization.TolerancePolicyConfig.builder()
                        .withinTolerance("report")
                        .reportSamples(5)
                        .build())
                .build());

        CompareRequest request = factory.create(config);

        assertEquals("report", request.getExecutionOptions().getWithinTolerance());
        assertEquals(5, request.getExecutionOptions().getWithinToleranceSamples());
    }

    @Test
    void shouldRejectUnknownWithinToleranceMode() {
        CliConfiguration config = configWithComparison();
        config.setNormalization(com.consilens.cli.model.normalization.NormalizationConfig.builder()
                .tolerance(com.consilens.cli.model.normalization.TolerancePolicyConfig.builder()
                        .withinTolerance("ignore-diff")
                        .build())
                .build());

        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> factory.create(config));
        assertTrue(failure.getMessage().contains("withinTolerance"),
                "拼错的口径值必须报错，不能静默按默认值跑");
    }

    private CliConfiguration configWithComparison() {
        CliConfiguration config = baseConfig();
        config.setComparison(ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of("amount")).build())
                .build());
        return config;
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
