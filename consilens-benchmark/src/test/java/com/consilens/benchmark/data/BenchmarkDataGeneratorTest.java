package com.consilens.benchmark.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkDataGeneratorTest {

    @Test
    void generatesBatchedRowsAndDeterministicMismatches() throws Exception {
        Path report = Files.createTempFile("benchmark-data", ".json");
        BenchmarkDataOptions options = BenchmarkDataOptions.parse(new String[]{
                "--jdbc-url", "jdbc:h2:mem:generator-test;DB_CLOSE_DELAY=-1",
                "--rows", "100",
                "--diff-ratio", "0.10",
                "--batch-size", "17",
                "--report", report.toString()
        });

        BenchmarkDataReport result = new BenchmarkDataGenerator().generate(options);

        assertThat(result.getSourceRows()).isEqualTo(100);
        assertThat(result.getTargetRows()).isEqualTo(100);
        assertThat(result.getActualMismatchRows()).isEqualTo(10);
        assertThat(result.getActualSourceMissingRows()).isZero();
        assertThat(result.getActualTargetMissingRows()).isZero();
        assertThat(Files.size(report)).isPositive();
    }

    @Test
    void resumeContinuesFromMaximumIdWithoutDuplicatingRows() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:resume-test;DB_CLOSE_DELAY=-1";
        Path firstReport = Files.createTempFile("benchmark-data-first", ".json");
        BenchmarkDataOptions first = BenchmarkDataOptions.parse(new String[]{
                "--jdbc-url", jdbcUrl,
                "--rows", "50",
                "--diff-ratio", "0",
                "--report", firstReport.toString()
        });
        new BenchmarkDataGenerator().generate(first);

        Path secondReport = Files.createTempFile("benchmark-data-second", ".json");
        BenchmarkDataOptions resumed = BenchmarkDataOptions.parse(new String[]{
                "--jdbc-url", jdbcUrl,
                "--rows", "100",
                "--diff-ratio", "0",
                "--resume",
                "--report", secondReport.toString()
        });
        BenchmarkDataReport result = new BenchmarkDataGenerator().generate(resumed);

        assertThat(result.getSourceRows()).isEqualTo(100);
        assertThat(result.getTargetRows()).isEqualTo(100);
        assertThat(result.getActualMismatchRows()).isZero();
    }

    @Test
    void reportsBothMissingDirectionsSeparately() throws Exception {
        Path report = Files.createTempFile("benchmark-data-missing", ".json");
        BenchmarkDataOptions options = BenchmarkDataOptions.parse(new String[]{
                "--jdbc-url", "jdbc:h2:mem:missing-test;DB_CLOSE_DELAY=-1",
                "--rows", "1000",
                "--diff-ratio", "0",
                "--source-missing-ratio", "0.10",
                "--target-missing-ratio", "0.20",
                "--report", report.toString()
        });

        BenchmarkDataReport result = new BenchmarkDataGenerator().generate(options);

        assertThat(result.getActualSourceMissingRows()).isEqualTo(100);
        assertThat(result.getActualTargetMissingRows()).isEqualTo(200);
        assertThat(result.getActualTotalDifferences()).isEqualTo(300);
        assertThat(result.getSourceRows()).isEqualTo(900);
        assertThat(result.getTargetRows()).isEqualTo(800);
    }

    @Test
    void supportsSparseAndSkewedKeysWithResumeSequence() throws Exception {
        for (String distribution : new String[]{"sparse", "skewed"}) {
            String jdbcUrl = "jdbc:h2:mem:key-" + distribution + ";DB_CLOSE_DELAY=-1";
            Path report = Files.createTempFile("benchmark-data-key", ".json");
            BenchmarkDataOptions first = BenchmarkDataOptions.parse(new String[]{
                    "--jdbc-url", jdbcUrl, "--rows", "20", "--diff-ratio", "0",
                    "--key-distribution", distribution, "--report", report.toString()
            });
            new BenchmarkDataGenerator().generate(first);

            BenchmarkDataOptions resumed = BenchmarkDataOptions.parse(new String[]{
                    "--jdbc-url", jdbcUrl, "--rows", "40", "--diff-ratio", "0", "--resume",
                    "--key-distribution", distribution, "--report", report.toString()
            });
            BenchmarkDataReport result = new BenchmarkDataGenerator().generate(resumed);

            assertThat(result.getSourceRows()).isEqualTo(40);
            assertThat(result.getTargetRows()).isEqualTo(40);
            assertThat(result.getKeyDistribution()).isEqualTo(distribution);
        }
    }
}
