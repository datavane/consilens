package com.consilens.benchmark.runner;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkOptionsTest {

    @Test
    void parsesE2eSamplingAccuracyAndDatasetIdentity() {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--mode", "e2e",
                "--e2e-warmup-runs", "2",
                "--e2e-measurement-runs", "7",
                "--expected-differences", "G01=123,C-CONCAT=123",
                "--dataset-id", "100m-sparse-diff5",
                "--key-distribution", "sparse",
                "--difference-type", "mismatch",
                "--scenario-config", "CUSTOM=/tmp/custom.yaml"
        });

        assertThat(options.e2eWarmupRuns()).isEqualTo(2);
        assertThat(options.e2eMeasurementRuns()).isEqualTo(7);
        assertThat(options.expectedDifferences("G01")).isEqualTo(123L);
        assertThat(options.datasetId()).isEqualTo("100m-sparse-diff5");
        assertThat(options.keyDistribution()).isEqualTo("sparse");
        assertThat(options.differenceType()).isEqualTo("mismatch");
        assertThat(options.scenarioConfigs()).containsEntry("CUSTOM", Path.of("/tmp/custom.yaml"));
    }

    @Test
    void rejectsE2eWithoutMeasurementSamples() {
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[]{
                "--e2e-measurement-runs", "0"
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveRowsAndOutOfRangeDiffRatios() {
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[]{"--rows", "abc"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--rows");
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[]{"--diff-ratio", "1.5"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--diff-ratio");
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[]{"--diff-ratio", "NaN"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--diff-ratio");
    }
}
