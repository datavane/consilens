package com.consilens.cli.service;

import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ComparisonConfig;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.cli.model.ListPairConfig;
import com.consilens.cli.model.StrategyConfig;
import com.consilens.cli.model.StringPairConfig;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.model.TablePath;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.SegmentResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffServiceTest {

    @Test
    void shouldPublishDifferencesBeforeWritingFinalResult() throws Exception {
        DiffRow diffRow = DiffRow.modified(
                List.of(1),
                List.of("alice"),
                List.of("bob"),
                List.of("name"),
                List.of("name"),
                List.of("name"),
                List.of("name"));
        DiffResult coreResult = DiffResult.builder()
                .differences(List.of(diffRow))
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(1)
                        .targetRowCount(1)
                        .mismatchCount(1)
                        .totalDifferences(1)
                        .build())
                .infoTree(Optional.empty())
                .completedAt(Instant.now())
                .metadata(new HashMap<>())
                .sourceTablePath(TablePath.of("src.users"))
                .targetTablePath(TablePath.of("tgt.users"))
                .build();

        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DiffContext context = DiffContext.builder().taskId("task-1").build();
        DiffService service = new TestableDiffService(new StubCompareRuntime(coreResult), lifecycle, context);

        service.performDiff(minimalConfig());

        assertEquals(List.of("start", "diffs", "complete", "close"), lifecycle.events);
        assertEquals(List.of(diffRow), lifecycle.publishedDifferences);
        assertEquals(coreResult, lifecycle.completedResult);
    }

    private CliConfiguration minimalConfig() {
        return CliConfiguration.builder()
                .source(ConnectionConfig.builder()
                        .type("mysql")
                        .name("source")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/source_db")
                                .username("user")
                                .password("pwd")
                                .build())
                        .build())
                .target(ConnectionConfig.builder()
                        .type("mysql")
                        .name("target")
                        .connection(ConnectionConfig.ConnectorConnectionProperties.builder()
                                .url("jdbc:mysql://localhost:3306/target_db")
                                .username("user")
                                .password("pwd")
                                .build())
                        .build())
                .comparison(ComparisonConfig.builder()
                        .tables(StringPairConfig.builder()
                                .source("src.users")
                                .target("tgt.users")
                                .build())
                        .keys(ListPairConfig.builder()
                                .source(List.of("id"))
                                .target(List.of("id"))
                                .build())
                        .build())
                .strategy(StrategyConfig.builder()
                        .mode("checksum")
                        .algorithm("concat")
                        .build())
                .build();
    }

    private static final class TestableDiffService extends DiffService {
        private final CompareRuntime runtime;
        private final DiffLifecycle lifecycle;
        private final DiffContext context;

        private TestableDiffService(CompareRuntime runtime, DiffLifecycle lifecycle, DiffContext context) {
            this.runtime = runtime;
            this.lifecycle = lifecycle;
            this.context = context;
        }

        @Override
        protected CompareRuntime createCompareRuntime() {
            return runtime;
        }

        @Override
        protected DiffLifecycle buildLifecycle(CliConfiguration config) {
            return lifecycle;
        }

        @Override
        protected DiffContext buildDiffContext(CliConfiguration config) {
            return context;
        }
    }

    private static final class StubCompareRuntime implements CompareRuntime {
        private final DiffResult result;

        private StubCompareRuntime(DiffResult result) {
            this.result = result;
        }

        @Override
        public DiffResult execute(CompareRequest request) {
            return result;
        }
    }

    private static final class RecordingLifecycle implements DiffLifecycle {
        private final List<String> events = new ArrayList<>();
        private final List<DiffRow> publishedDifferences = new ArrayList<>();
        private DiffResult completedResult;

        @Override
        public void onDiffStart(DiffContext context) {
            events.add("start");
        }

        @Override
        public void onSegmentComplete(SegmentResult result) {
            // no-op
        }

        @Override
        public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) {
            events.add("diffs");
            publishedDifferences.addAll(diffs);
        }

        @Override
        public void onDiffComplete(DiffResult result, DiffContext context) {
            events.add("complete");
            completedResult = result;
        }

        @Override
        public void onDiffError(DiffContext context, Throwable error) {
            // no-op
        }

        @Override
        public void close() {
            events.add("close");
        }
    }
}
