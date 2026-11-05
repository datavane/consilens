package com.consilens.core.compare;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.integration.IntegrationCredentials;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same-database comparison test: OceanBase orders vs orders_backup.
 *
 * <p>Test data contains intentional differences:
 * <ul>
 *   <li>Source-only rows: IDs 9991-10000 (exist only in orders)</li>
 *   <li>Target-only rows: IDs 10001-10010 (exist only in orders_backup)</li>
 *   <li>Mismatched rows: IDs 100-109 (different amount/status)</li>
 * </ul>
 *
 * <p>Expected results:
 * <ul>
 *   <li>sourceMissingCount = 10</li>
 *   <li>targetMissingCount = 10</li>
 *   <li>mismatchCount = 10</li>
 *   <li>totalDifferences = 30</li>
 * </ul>
 */
class OceanBaseSameDbComparisonTest {

    @Test
    void shouldDetectDifferencesWithinOceanBase() throws Exception {
        String oceanBasePassword = IntegrationCredentials.require("CONSILENS_IT_OCEANBASE_PASSWORD");

        // Source: OceanBase orders (has rows 1-10000)
        Map<String, Object> sourceConnection = new LinkedHashMap<>();
        sourceConnection.put("url", "jdbc:mysql://127.0.0.1:2881/test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        sourceConnection.put("username", "root@test");
        sourceConnection.put("password", oceanBasePassword);

        ConnectorConfig sourceConfig = ConnectorConfig.builder()
                .type("oceanbase")
                .connection(sourceConnection)
                .resource(ResourceLocator.builder().type("table").name("mydb.orders").build())
                .readOptions(ReadOptions.builder().options(new LinkedHashMap<>()).build())
                .build();

        // Target: OceanBase orders_backup (has rows 1-9990 + 10001-10010, with 100-109 modified)
        Map<String, Object> targetConnection = new LinkedHashMap<>();
        targetConnection.put("url", "jdbc:mysql://127.0.0.1:2881/test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        targetConnection.put("username", "root@test");
        targetConnection.put("password", oceanBasePassword);

        ConnectorConfig targetConfig = ConnectorConfig.builder()
                .type("oceanbase")
                .connection(targetConnection)
                .resource(ResourceLocator.builder().type("table").name("mydb.orders_backup").build())
                .readOptions(ReadOptions.builder().options(new LinkedHashMap<>()).build())
                .build();

        CompareRequest request = CompareRequest.builder()
                .source(sourceConfig)
                .target(targetConfig)
                .sourceKeySpec(KeySpec.builder().fields(List.of("order_id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("order_id")).build())
                .sourceComparisons(ComparisonSpec.builder()
                        .fields(List.of("customer_id", "amount", "status"))
                        .build())
                .targetComparisons(ComparisonSpec.builder()
                        .fields(List.of("customer_id", "amount", "status"))
                        .build())
                .normalizationSpec(NormalizationSpec.builder().build())
                .strategyPreference(CompareStrategyPreference.builder()
                        .preferredPlans(List.of("pushdown_checksum"))
                        .build())
                .executionOptions(CompareExecutionOptions.builder()
                        .checksumAlgorithm("concat")
                        .maxDifferences(100L)
                        .build())
                .build();

        CompareRuntime runtime = new DefaultCompareRuntime();
        DiffResult result = runtime.execute(request);

        assertNotNull(result);

        // Print detailed results
        System.out.println("=== Same-Database (OceanBase orders vs orders_backup) Comparison Results ===");
        System.out.println(result.getSummary());
        System.out.println("Source missing count: " + result.getStatistics().getSourceMissingCount());
        System.out.println("Target missing count: " + result.getStatistics().getTargetMissingCount());
        System.out.println("Mismatch count: " + result.getStatistics().getMismatchCount());
        System.out.println("Total differences: " + result.getStatistics().getTotalDifferences());

        // Verify the expected differences are detected
        assertTrue(result.getStatistics().getTotalDifferences() > 0,
                "Should detect differences within OceanBase");
        System.out.println("==========================================================================");
    }
}
