package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.ListPairConfig;
import com.consilens.cli.model.LocalCompareConfig;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.cli.model.StringPairConfig;
import com.consilens.cli.model.normalization.TypeNormalizationRule;
import com.consilens.connector.api.normalization.NormalizationRule;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.SegmentResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

    @Test
    void shouldPublishDifferencesBeforeCompletingLifecycle() throws Exception {
        RecordingLifecycle lifecycle = new RecordingLifecycle(false);
        DiffResult diffResult = DiffResult.of(
                List.of(DiffRow.removed(List.of(1), List.of("Alice"), List.of("name"))),
                com.consilens.connector.api.model.TablePath.of("source_table"),
                com.consilens.connector.api.model.TablePath.of("target_table"));

        TestableDiffService service = new TestableDiffService(lifecycle, request -> diffResult);

        service.performDiff(createConfig());

        assertEquals(List.of("start", "differences", "complete", "close"), lifecycle.events);
    }

    @Test
    void shouldFailWhenLifecycleCloseFailsAfterSuccessfulDiff() {
        RecordingLifecycle lifecycle = new RecordingLifecycle(true);
        DiffResult diffResult = DiffResult.of(
                List.of(DiffRow.removed(List.of(1), List.of("Alice"), List.of("name"))),
                com.consilens.connector.api.model.TablePath.of("source_table"),
                com.consilens.connector.api.model.TablePath.of("target_table"));

        TestableDiffService service = new TestableDiffService(lifecycle, request -> diffResult);

        Exception exception = assertThrows(Exception.class, () -> service.performDiff(createConfig()));
        assertTrue(exception.getMessage().contains("Lifecycle close failed"));
        assertEquals(List.of("start", "differences", "complete", "close"), lifecycle.events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldConvertTemporalComparisonModeToNormalizationRule() throws Exception {
        DiffService service = new DiffService();
        TypeNormalizationRule rule = new TypeNormalizationRule();
        rule.setTimezone("UTC");
        rule.setComparisonMode("DATE_ONLY");

        Method method = DiffService.class.getDeclaredMethod("toNormalizationRules", String.class, TypeNormalizationRule.class);
        method.setAccessible(true);

        List<NormalizationRule> rules = (List<NormalizationRule>) method.invoke(service, "timestamp", rule);

        assertEquals(1, rules.size());
        assertEquals("format_datetime", rules.get(0).getOperation());
        assertEquals(Map.of("timezone", "UTC", "comparisonMode", "DATE_ONLY"), rules.get(0).getParams());
    }

    private CliConfiguration createConfig() {
        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .url("jdbc:mysql://localhost:3306/source_db")
                        .username("user")
                        .password("pwd")
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("source_table").build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .url("jdbc:mysql://localhost:3306/target_db")
                        .username("user")
                        .password("pwd")
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("target_table").build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .tables(StringPairConfig.builder().source("source_table").target("target_table").build())
                        .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                        .fields(ListPairConfig.builder().source(List.of("name")).target(List.of("name")).build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("concat")
                        .bisectionFactor(4)
                        .bisectionThreshold(1000L)
                        .batchSize(100)
                        .enableProfiling(false)
                        .localCompare(LocalCompareConfig.builder().mode("full").build())
                        .build())
                .build();
    }

    private static class TestableDiffService extends DiffService {
        private final DiffLifecycle lifecycle;
        private final CompareRuntime runtime;

        private TestableDiffService(DiffLifecycle lifecycle, CompareRuntime runtime) {
            this.lifecycle = lifecycle;
            this.runtime = runtime;
        }

        @Override
        protected CompareRuntime createCompareRuntime() {
            return runtime;
        }

        @Override
        protected DiffLifecycle buildLifecycle(CliConfiguration config) {
            return lifecycle;
        }
    }

    private static class RecordingLifecycle implements DiffLifecycle {
        private final boolean failOnClose;
        private final List<String> events = new ArrayList<>();

        private RecordingLifecycle(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override
        public void onDiffStart(DiffContext context) {
            events.add("start");
        }

        @Override
        public void onSegmentComplete(SegmentResult result) {
        }

        @Override
        public void onDifferencesFound(List<com.consilens.core.diff.DiffRow> diffs, DiffContext context) {
            events.add("differences");
        }

        @Override
        public void onDiffComplete(DiffResult result, DiffContext context) {
            events.add("complete");
        }

        @Override
        public void onDiffError(DiffContext context, Throwable error) {
            events.add("error");
        }

        @Override
        public void close() throws Exception {
            events.add("close");
            if (failOnClose) {
                throw new Exception("close failed");
            }
        }
    }
}
