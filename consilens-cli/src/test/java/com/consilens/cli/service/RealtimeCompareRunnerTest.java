package com.consilens.cli.service;

import com.consilens.cli.model.CheckpointStoreConfig;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.ListPairConfig;
import com.consilens.cli.model.LocalCompareConfig;
import com.consilens.cli.model.RealtimeConfig;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.cli.model.StringPairConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeCompareRunnerTest {

    @Test
    void shouldAdvanceCheckpointOnSuccessfulRun() throws Exception {
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        RecordingDiffService diffService = new RecordingDiffService(false, true);
        RealtimeCompareRunner runner = new RealtimeCompareRunner(
                diffService,
                new CompareRequestFactory(),
                Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC),
                config -> checkpointStore);

        runner.runLoop(baseConfig());

        CompareCheckpoint checkpoint = checkpointStore.load(new CompareRequestFactory().create(baseConfig()).getRealtimeSpec().getTaskId()).orElseThrow();
        assertEquals(Instant.parse("2026-05-03T09:55:00Z"), checkpoint.getWatermark());
        assertTrue(diffService.lastSourceFilter.contains("updated_at >="));
        assertTrue(diffService.lastTargetFilter.contains("updated_at <"));
        assertTrue(diffService.lastSourceFilter.contains("'2026-05-03 09:45:00'"));
        assertTrue(diffService.lastSourceFilter.contains("'2026-05-03 09:55:00'"));
    }

    @Test
    void shouldKeepPreviousWatermarkOnFailure() throws Exception {
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        String taskId = new CompareRequestFactory().create(baseConfig()).getRealtimeSpec().getTaskId();
        checkpointStore.markSucceeded(taskId,
                Instant.parse("2026-05-03T09:40:00Z"),
                Instant.parse("2026-05-03T09:10:00Z"),
                Instant.parse("2026-05-03T09:40:00Z"));
        RealtimeCompareRunner runner = new RealtimeCompareRunner(
                new RecordingDiffService(true, true),
                new CompareRequestFactory(),
                Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC),
                config -> checkpointStore);

        CliDiffResult result = runner.runLoop(baseConfig());

        CompareCheckpoint checkpoint = checkpointStore.load(taskId).orElseThrow();
        assertEquals(0, result.getTotalDifferences());
        assertEquals(Instant.parse("2026-05-03T09:40:00Z"), checkpoint.getWatermark());
        assertEquals("failed", checkpoint.getStatus());
    }

    @Test
    void loopShouldKeepProcessAliveWhenIterationFails() throws Exception {
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        RecordingDiffService diffService = new RecordingDiffService(true, true);
        RealtimeCompareRunner runner = new RealtimeCompareRunner(
                diffService,
                new CompareRequestFactory(),
                Clock.fixed(Instant.parse("2026-05-03T10:00:00Z"), ZoneOffset.UTC),
                config -> checkpointStore);

        try {
            CliDiffResult result = runner.runLoop(baseConfig());

            assertEquals(0, result.getTotalDifferences());
            assertEquals(1, diffService.invocations);
        } finally {
            Thread.interrupted();
        }
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
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("orders_src").build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/target")
                                .username("root")
                                .password("secret")
                                .build())
                        .resource(ConnectionConfig.ResourceConfig.builder().type("table").name("orders_tgt").build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                        .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of("amount")).build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("xor")
                        .bisectionFactor(4)
                        .bisectionThreshold(1000L)
                        .localCompare(LocalCompareConfig.builder().mode("full").build())
                        .build())
                .realtime(RealtimeConfig.builder()
                        .enabled(true)
                        .updateColumns(StringPairConfig.builder().source("updated_at").target("updated_at").build())
                        .watermarkDelay("PT5M")
                        .windowSize("PT10M")
                        .overlap("PT30M")
                        .checkpointStore(CheckpointStoreConfig.builder().type("memory").name("ignored").build())
                        .build())
                .build();
    }

    private static final class RecordingDiffService extends DiffService {

        private final boolean fail;
        private final boolean interruptAfterCall;
        private String lastSourceFilter;
        private String lastTargetFilter;
        private int invocations;

        private RecordingDiffService(boolean fail) {
            this(fail, false);
        }

        private RecordingDiffService(boolean fail, boolean interruptAfterCall) {
            this.fail = fail;
            this.interruptAfterCall = interruptAfterCall;
        }

        @Override
        public CliDiffResult performDiff(CliConfiguration config) throws Exception {
            invocations++;
            lastSourceFilter = config.getComparison().getFilters().getSource();
            lastTargetFilter = config.getComparison().getFilters().getTarget();
            if (interruptAfterCall) {
                Thread.currentThread().interrupt();
            }
            if (fail) {
                throw new Exception("boom");
            }
            return CliDiffResult.builder()
                    .strategy(config.getStrategyMode())
                    .sourceMissingCount(0)
                    .targetMissingCount(0)
                    .mismatchCount(0)
                    .totalDifferences(0)
                    .sourceRowCount(1)
                    .targetRowCount(1)
                    .differences(List.of())
                    .build();
        }
    }
}
