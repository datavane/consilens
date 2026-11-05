package com.consilens.cli.service;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbCompareRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiffCsvFilesWithDuckDb() throws Exception {
        Path source = write("source.csv", "id,name,amount\n"
                + "1,alice,10\n2,bob,20\n3,carol,30\n");
        Path target = write("target.csv", "id,name,amount\n"
                + "1,alice,10\n2,bob,21\n4,dave,40\n");

        DiffResult result = new DuckDbCompareRuntime().execute(request(source, target, null));

        assertThat(result.getStatistics().getSourceRowCount()).isEqualTo(3L);
        assertThat(result.getStatistics().getTargetRowCount()).isEqualTo(3L);
        assertThat(result.getStatistics().getMismatchCount()).isEqualTo(1L);
        assertThat(result.getStatistics().getSourceMissingCount()).isEqualTo(1L);
        assertThat(result.getStatistics().getTargetMissingCount()).isEqualTo(1L);
        assertThat(result.getStatistics().getTotalDifferences()).isEqualTo(3L);
        assertThat(result.getDifferences())
                .extracting(row -> row.getOperation())
                .containsExactlyInAnyOrder(DiffOperation.MISMATCH,
                        DiffOperation.SOURCE_MISSING, DiffOperation.TARGET_MISSING);
        assertThat(result.getDifferences().stream()
                .filter(row -> row.getOperation() == DiffOperation.MISMATCH)
                .findFirst().orElseThrow().getChangedColumns1())
                .containsExactly("amount");
    }

    @Test
    void shouldCompareAllNonKeyColumnsWhenFieldsAreOmitted() throws Exception {
        Path source = write("implicit-source.csv", "id,name,amount\n1,alice,10\n2,bob,20\n");
        Path target = write("implicit-target.csv", "id,name,amount\n1,alice,10\n2,bob,99\n");

        DiffResult result = new DuckDbCompareRuntime().execute(
                request(source, target, null, "csv", List.of("id"), List.of()));

        // 未声明比较列时必须回退到"除主键外的所有列"，否则 amount 的差异会被静默吞掉
        assertThat(result.getStatistics().getMismatchCount()).isEqualTo(1L);
        assertThat(result.getStatistics().getTotalDifferences()).isEqualTo(1L);
        assertThat(result.getDifferences().get(0).getChangedColumns1()).containsExactly("amount");
    }

    @Test
    void shouldHonorExcludeWhenFieldsAreOmitted() throws Exception {
        // remark 列有差异但被 exclude：不应算作差异
        Path source = write("exclude-source.csv", "id,name,remark\n1,alice,a\n2,bob,b\n");
        Path target = write("exclude-target.csv", "id,name,remark\n1,alice,A\n2,bob,b\n");

        var request = CompareRequest.builder()
                .source(config("source", source, "csv"))
                .target(config("target", target, "csv"))
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().exclude(List.of("remark")).build())
                .targetComparisons(ComparisonSpec.builder().exclude(List.of("remark")).build())
                .build();

        DiffResult result = new DuckDbCompareRuntime().execute(request);

        // name 两侧一致、remark 被 exclude：不应产生差异
        assertThat(result.getStatistics().getTotalDifferences()).isZero();
    }

    @Test
    void shouldPairInferredColumnsByNameNotByPosition() throws Exception {
        // 两侧列顺序不同但列名相同：按下标配对会把 name 和 amount 拿来比，产生稳定误报
        Path source = write("order-source.csv", "id,name,amount\n1,alice,10\n");
        Path target = write("order-target.csv", "id,amount,name\n1,10,alice\n");

        DiffResult result = new DuckDbCompareRuntime().execute(
                request(source, target, null, "csv", List.of("id"), List.of()));

        assertThat(result.getStatistics().getTotalDifferences()).isZero();
    }

    @Test
    void shouldRejectImplicitComparisonWhenColumnSetsDiffer() throws Exception {
        Path source = write("set-source.csv", "id,name\n1,alice\n");
        Path target = write("set-target.csv", "id,amount\n1,10\n");

        // 列集合不同时必须让用户显式声明映射，而不是悄悄按下标配对
        assertThatThrownBy(() -> new DuckDbCompareRuntime().execute(
                request(source, target, null, "csv", List.of("id"), List.of())))
                .hasMessageContaining("declare comparison fields");
    }

    @Test
    void shouldDiffParquetFilesWithDuckDb() throws Exception {
        Path source = tempDir.resolve("source.parquet");
        Path target = tempDir.resolve("target.parquet");
        writeParquet(source, "SELECT 1 AS id, 'a' AS name, 10 AS amount "
                + "UNION ALL SELECT 2, 'b', 20");
        writeParquet(target, "SELECT 1 AS id, 'a' AS name, 10 AS amount "
                + "UNION ALL SELECT 2, 'b', 21");

        DiffResult result = new DuckDbCompareRuntime().execute(
                request(source, target, null, "parquet"));

        assertThat(result.getStatistics().getMismatchCount()).isEqualTo(1L);
        assertThat(result.getDifferences().get(0).getChangedColumns1()).containsExactly("amount");
    }

    @Test
    void shouldRejectMismatchedKeyTypes() throws Exception {
        Path source = write("leading-source.csv", "id,name\n001,alice\n");
        Path target = write("leading-target.csv", "id,name\n1,alice\n");

        assertThatThrownBy(() -> new DuckDbCompareRuntime().execute(request(source, target, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching key types");
    }

    @Test
    void shouldRejectMismatchedComparisonTypes() throws Exception {
        Path source = write("field-source.csv", "id,code\n1,001\n");
        Path target = write("field-target.csv", "id,code\n1,1\n");

        assertThatThrownBy(() -> new DuckDbCompareRuntime().execute(
                request(source, target, null, "csv", List.of("id"), List.of("code"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching comparison field types");
    }

    @Test
    void shouldDiffCompositeKeys() throws Exception {
        Path source = write("composite-source.csv", "id,tenant,name\n"
                + "1,10,alice\n1,20,bob\n");
        Path target = write("composite-target.csv", "id,tenant,name\n"
                + "1,10,alice\n1,20,robert\n2,30,carol\n");

        DiffResult result = new DuckDbCompareRuntime().execute(
                request(source, target, null, "csv",
                        List.of("id", "tenant"), List.of("name")));

        assertThat(result.getStatistics().getMismatchCount()).isEqualTo(1L);
        assertThat(result.getStatistics().getSourceMissingCount()).isEqualTo(1L);
        assertThat(result.getDifferences()).anySatisfy(row -> {
            if (row.getOperation() == DiffOperation.MISMATCH) {
                assertThat(row.getPrimaryKey()).containsExactly(1L, 20L);
                assertThat(row.getChangedColumns1()).containsExactly("name");
            }
        });
    }

    @Test
    void shouldRejectDuplicateKeys() throws Exception {
        Path source = write("dupe-source.csv", "id,name,amount\n1,a,1\n1,b,2\n");
        Path target = write("dupe-target.csv", "id,name,amount\n1,a,1\n");

        assertThatThrownBy(() -> new DuckDbCompareRuntime().execute(request(source, target, null)))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("unique source keys");
    }

    @Test
    void shouldEnforceMaxDifferences() throws Exception {
        Path source = write("max-source.csv", "id,name,amount\n1,a,1\n2,b,2\n");
        Path target = write("max-target.csv", "id,name,amount\n1,a,9\n2,b,9\n");

        assertThatThrownBy(() -> new DuckDbCompareRuntime().execute(request(source, target, 1L)))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("maxDifferences=1");
    }

    @Test
    void shouldAllowDiffCountAtConfiguredLimit() throws Exception {
        Path source = write("limit-source.csv", "id,name,amount\n1,a,1\n");
        Path target = write("limit-target.csv", "id,name,amount\n");

        DiffResult result = new DuckDbCompareRuntime().execute(request(source, target, 1L));

        assertThat(result.getStatistics().getTotalDifferences()).isEqualTo(1L);
    }

    @Test
    void shouldCompareAgainstHeaderOnlyCsv() throws Exception {
        Path source = write("full-source.csv", "id,name,amount\n1,a,1\n2,b,2\n");
        Path target = write("header-only.csv", "id,name,amount\n");

        DiffResult result = new DuckDbCompareRuntime().execute(request(source, target, null));

        assertThat(result.getStatistics().getTargetMissingCount()).isEqualTo(2L);
        assertThat(result.getStatistics().getTotalDifferences()).isEqualTo(2L);
    }

    private CompareRequest request(Path source, Path target, Long maxDifferences) {
        return request(source, target, maxDifferences, "csv");
    }

    private CompareRequest request(Path source, Path target, Long maxDifferences, String format) {
        return request(source, target, maxDifferences, format,
                List.of("id"), List.of("name", "amount"));
    }

    private CompareRequest request(Path source, Path target, Long maxDifferences, String format,
                                   List<String> keys, List<String> fields) {
        var builder = CompareRequest.builder()
                .source(config("source", source, format))
                .target(config("target", target, format))
                .sourceKeySpec(KeySpec.builder().fields(keys).build())
                .targetKeySpec(KeySpec.builder().fields(keys).build())
                .sourceComparisons(ComparisonSpec.builder().fields(fields).build())
                .targetComparisons(ComparisonSpec.builder().fields(fields).build());
        if (maxDifferences != null) {
            builder.executionOptions(CompareExecutionOptions.builder()
                    .maxDifferences(maxDifferences)
                    .build());
        }
        return builder.build();
    }

    private ConnectorConfig config(String name, Path path) {
        return config(name, path, "csv");
    }

    private ConnectorConfig config(String name, Path path, String format) {
        return ConnectorConfig.builder()
                .type("file")
                .name(name)
                .resource(ResourceLocator.builder()
                        .type("file")
                        .path(path.toString())
                        .options(Map.of("format", format))
                        .build())
                .build();
    }

    private void writeParquet(Path path, String select) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '"
                    + path.toAbsolutePath().toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
