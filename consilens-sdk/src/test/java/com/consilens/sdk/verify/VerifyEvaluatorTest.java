package com.consilens.sdk.verify;

import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassWhenNoDifferencesAndFailOnAny() {
        DiffResult result = result(List.of(), 10, 10, 0, 0, 0);

        VerifyResult verify = VerifyEvaluator.evaluate(result, policy(FailOn.ANY, null, null));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(verify.getExitCode()).isZero();
        assertThat(verify.getSummary().getTotalDifferences()).isZero();
    }

    @Test
    void shouldFailWhenDifferencesAndFailOnAny() {
        DiffResult result = result(mismatchRows(2), 10, 10, 0, 0, 2);

        VerifyResult verify = VerifyEvaluator.evaluate(result, policy(FailOn.ANY, null, null));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(verify.getExitCode()).isEqualTo(1);
        assertThat(verify.getChecks()).anySatisfy(check -> {
            assertThat(check.getName()).isEqualTo("anyDifference");
            assertThat(check.getPassed()).isFalse();
        });
    }

    @Test
    void shouldEvaluateMismatchRate() {
        DiffResult result = result(mismatchRows(2), 100, 100, 0, 0, 2);

        // failOn=NONE 隔离失败率检查本身，避免 anyDifference 干扰断言
        VerifyResult overLimit = VerifyEvaluator.evaluate(result,
                policy(FailOn.NONE, 0.005, null));
        VerifyResult withinLimit = VerifyEvaluator.evaluate(result,
                policy(FailOn.NONE, 0.02, null));

        assertThat(overLimit.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(withinLimit.getVerdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void shouldEvaluateRowCountDelta() {
        DiffResult result = result(List.of(), 100, 110, 10, 0, 0);

        VerifyResult overLimit = VerifyEvaluator.evaluate(result,
                policy(FailOn.NONE, null, 5.0));
        VerifyResult withinLimit = VerifyEvaluator.evaluate(result,
                policy(FailOn.NONE, null, 10.0));

        assertThat(overLimit.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(withinLimit.getVerdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void shouldBeInconclusiveWhenFailOnNewWithoutBaseline() {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);

        VerifyResult verify = VerifyEvaluator.evaluate(result, policy(FailOn.NEW, null, null));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(verify.getExitCode()).isEqualTo(3);
    }

    @Test
    void shouldClassifyDeltaAgainstBaseline() throws Exception {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);
        String row = "{\"operation\":\"mismatch\",\"primaryKey\":[0],"
                + "\"metadata\":{\"changedColumns1\":[\"amount\"],\"changedColumns2\":[\"amount\"]}}";
        Path same = tempDir.resolve("same.jsonl");
        Files.writeString(same, row);
        Path different = tempDir.resolve("different.jsonl");
        Files.writeString(different, "{\"operation\":\"mismatch\",\"primaryKey\":[999],\"metadata\":{}}");

        VerifyResult pass = VerifyEvaluator.evaluate(result,
                policyWithBaseline(FailOn.NEW, same, 0L));
        VerifyResult fail = VerifyEvaluator.evaluate(result,
                policyWithBaseline(FailOn.NEW, different, 0L));

        assertThat(pass.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(pass.getDelta().getPersistingDifferences()).isEqualTo(1);
        assertThat(fail.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(fail.getDelta().getNewDifferences()).isEqualTo(1);
    }

    @Test
    void shouldStillReportDeltaAndHonorMaxNewWhenFailOnIsAny() throws Exception {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);
        Path same = tempDir.resolve("any-same.jsonl");
        Files.writeString(same, "{\"operation\":\"mismatch\",\"primaryKey\":[0],"
                + "\"metadata\":{\"changedColumns1\":[\"amount\"],\"changedColumns2\":[\"amount\"]}}");

        // failOn=any 时 baseline 仍要算出 delta，否则报告里的 0/0/0 是错的
        VerifyResult verdict = VerifyEvaluator.evaluate(result,
                policyWithBaseline(FailOn.ANY, same, 0L));

        assertThat(verdict.getDelta().getPersistingDifferences()).isEqualTo(1);
        assertThat(verdict.getDelta().getNewDifferences()).isZero();
        // anyDifference 仍然生效（用户显式选了 any），增量检查同时被记录
        assertThat(verdict.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(verdict.getChecks())
                .extracting("name")
                .contains("anyDifference", "maxNewDifferences");
    }

    @Test
    void shouldCountDuplicateKeyOccurrencesAgainstBaseline() throws Exception {
        // 同一身份出现 3 次（重复键场景），baseline 只记录 2 次：多的那 1 行是新增差异
        DiffRow row = DiffRow.modified(List.of(0),
                List.of("10.0"), List.of("11.0"),
                List.of("amount"), List.of("amount"),
                List.of("amount"), List.of("amount"));
        DiffResult result = result(List.of(row, row, row), 3, 3, 0, 0, 3);
        String line = "{\"operation\":\"mismatch\",\"primaryKey\":[0],"
                + "\"metadata\":{\"changedColumns1\":[\"amount\"],\"changedColumns2\":[\"amount\"]}}";
        Path baseline = tempDir.resolve("dupe.jsonl");
        Files.writeString(baseline, line + "\n" + line + "\n");

        VerifyResult verify = VerifyEvaluator.evaluate(result,
                policyWithBaseline(FailOn.NEW, baseline, 0L));

        assertThat(verify.getDelta().getNewDifferences()).isEqualTo(1);
        assertThat(verify.getDelta().getPersistingDifferences()).isEqualTo(2);
        assertThat(verify.getVerdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void shouldExplainWhyVerdictIsInconclusive() {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);

        VerifyResult verify = VerifyEvaluator.evaluate(result, policy(FailOn.NEW, null, null));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(verify.getMessage()).contains("baseline");
    }

    @Test
    void shouldAcceptLegacyDisplayNameInBaseline() throws Exception {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);
        Path legacy = tempDir.resolve("legacy.jsonl");
        Files.writeString(legacy, "{\"operation\":\"MISMATCH\",\"primaryKey\":[0],"
                + "\"metadata\":{\"changedColumns1\":[\"amount\"],\"changedColumns2\":[\"amount\"]}}");

        VerifyResult verify = VerifyEvaluator.evaluate(result,
                policyWithBaseline(FailOn.NEW, legacy, 0L));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(verify.getDelta().getNewDifferences()).isZero();
        assertThat(verify.getDelta().getPersistingDifferences()).isEqualTo(1);
    }

    @Test
    void shouldFailOnMissingRequiredColumns() {
        DiffResult result = result(mismatchRows(1), 1, 1, 0, 0, 1);

        VerifyResult verify = VerifyEvaluator.evaluate(result,
                VerifyPolicy.builder()
                        .failOn(FailOn.NONE)
                        .requiredColumns(List.of("amount", "missing_column"))
                        .build());

        assertThat(verify.getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(verify.getChecks()).anySatisfy(check -> {
            assertThat(check.getName()).isEqualTo("requiredColumns");
            assertThat(check.getPassed()).isFalse();
        });
    }

    @Test
    void shouldPassAllChecksWhenFailOnNone() {
        DiffResult result = result(mismatchRows(2), 100, 100, 0, 0, 2);

        VerifyResult verify = VerifyEvaluator.evaluate(result, policy(FailOn.NONE, null, null));

        assertThat(verify.getVerdict()).isEqualTo(Verdict.PASS);
        assertThat(verify.getExitCode()).isZero();
    }

    private static VerifyPolicy policy(FailOn failOn, Double maxMismatchRate, Double maxRowCountDeltaPct) {
        return VerifyPolicy.builder()
                .failOn(failOn)
                .maxMismatchRate(maxMismatchRate)
                .maxRowCountDeltaPct(maxRowCountDeltaPct)
                .build();
    }

    private static VerifyPolicy policyWithBaseline(FailOn failOn, Path baseline, Long maxNew) {
        return VerifyPolicy.builder()
                .failOn(failOn)
                .baselineFile(baseline)
                .maxNewDifferences(maxNew)
                .build();
    }

    private static DiffResult result(List<DiffRow> rows,
                                     long sourceRows,
                                     long targetRows,
                                     long sourceMissing,
                                     long targetMissing,
                                     long mismatches) {
        return DiffResult.builder()
                .differences(rows)
                .statistics(DiffResult.DiffStatistics.builder()
                        .sourceRowCount(sourceRows)
                        .targetRowCount(targetRows)
                        .sourceMissingCount(sourceMissing)
                        .targetMissingCount(targetMissing)
                        .mismatchCount(mismatches)
                        .totalDifferences(sourceMissing + targetMissing + mismatches)
                        .build())
                .build();
    }

    private static List<DiffRow> mismatchRows(int count) {
        java.util.List<DiffRow> rows = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(DiffRow.modified(
                    List.of(index),
                    List.of("10.0"), List.of("11.0"),
                    List.of("amount"), List.of("amount"),
                    List.of("amount"), List.of("amount")));
        }
        return rows;
    }
}
