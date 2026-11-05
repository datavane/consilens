package com.consilens.benchmark.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliProcessRunnerTest {

    @Test
    void parsesOnlyStableMachineReadableMetrics() {
        String output = "queryCount=999 should not match\n"
                + "INFO BENCHMARK_METRIC instrumentedQueryCount=17\n"
                + "INFO BENCHMARK_METRIC heapPeakBytes=123456\n";

        Map<String, Double> metrics = CliProcessRunner.parseBenchmarkMetrics(output);

        assertThat(metrics).containsEntry("instrumentedQueryCount", 17.0)
                .containsEntry("heapPeakBytes", 123456.0)
                .doesNotContainKey("queryCount");
    }
}
