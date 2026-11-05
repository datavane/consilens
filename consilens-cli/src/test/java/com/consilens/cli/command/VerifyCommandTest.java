package com.consilens.cli.command;

import com.consilens.sdk.verify.FailOn;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifyCommandTest {

    @Test
    void shouldParseFailOnValues() {
        assertThat(VerifyCommand.parseFailOn("any")).isEqualTo(FailOn.ANY);
        assertThat(VerifyCommand.parseFailOn("NEW")).isEqualTo(FailOn.NEW);
        assertThat(VerifyCommand.parseFailOn("none")).isEqualTo(FailOn.NONE);
        assertThatThrownBy(() -> VerifyCommand.parseFailOn("sometimes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRequireBaselineForFailOnNew() {
        assertThatThrownBy(() -> VerifyCommand.buildPolicy(
                "new", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--baseline");
    }

    @Test
    void shouldBuildPolicyWithBaselineAndLimits() {
        VerifyPolicy policy = VerifyCommand.buildPolicy(
                "new", 0.001, 1.0, 5L, "/tmp/baseline.jsonl", List.of("amount"));

        assertThat(policy.getFailOn()).isEqualTo(FailOn.NEW);
        assertThat(policy.getMaxMismatchRate()).isEqualTo(0.001);
        assertThat(policy.getMaxRowCountDeltaPct()).isEqualTo(1.0);
        assertThat(policy.getMaxNewDifferences()).isEqualTo(5L);
        assertThat(policy.getBaselineFile()).isEqualTo(Paths.get("/tmp/baseline.jsonl"));
        assertThat(policy.getRequiredColumns()).containsExactly("amount");
    }

    @Test
    void shouldSelectVerifyEngine() {
        com.consilens.sdk.ConsilensEngine defaultEngine =
                new com.consilens.sdk.DefaultConsilensEngine(request -> null);
        assertThat(VerifyCommand.engineFor("java", defaultEngine)).isSameAs(defaultEngine);

        com.consilens.sdk.ConsilensEngine duckdb = VerifyCommand.engineFor("duckdb", defaultEngine);
        assertThat(duckdb).isInstanceOf(com.consilens.sdk.DefaultConsilensEngine.class);
        duckdb.close();

        assertThatThrownBy(() -> VerifyCommand.engineFor("spark", defaultEngine))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported verify engine");
    }

    @Test
    void shouldBuildWindowForVerify() {
        assertThat(VerifyCommand.buildWindow(null, null, null, null, null)).isNull();

        var window = VerifyCommand.buildWindow(null, "src_updated", "tgt_updated",
                "2026-01-01", "2026-02-01");
        assertThat(window.sourceColumn()).isEqualTo("src_updated");
        assertThat(window.targetColumn()).isEqualTo("tgt_updated");
        assertThat(window.getFrom()).isEqualTo("2026-01-01");
        assertThat(window.getTo()).isEqualTo("2026-02-01");

        assertThatThrownBy(() -> VerifyCommand.buildWindow(null, null, null,
                "2026-01-01", "2026-02-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-column");
    }

    @Test
    void shouldMapVerdictsToExitCodes() {
        assertThat(VerifyCommand.exitCodeOf(Verdict.PASS)).isZero();
        assertThat(VerifyCommand.exitCodeOf(Verdict.FAIL)).isEqualTo(1);
        assertThat(VerifyCommand.exitCodeOf(Verdict.ERROR)).isEqualTo(2);
        assertThat(VerifyCommand.exitCodeOf(Verdict.INCONCLUSIVE)).isEqualTo(3);
        assertThat(VerifyCommand.exitCodeOf(Verdict.BUDGET_EXCEEDED)).isEqualTo(4);
    }

    @Test
    void shouldReportBudgetExceededAsExitCodeFourEvenWhenWrapped() {
        // 引擎把预算异常包成 RuntimeException：若不沿 cause 链找，就只能落到"执行失败 = 2"
        RuntimeException wrapped = new RuntimeException("ChecksumDiff failed",
                new com.consilens.core.exception.BudgetExceededException(
                        "maxRowsScanned", 10L, 99L));

        assertThat(VerifyCommand.findBudget(wrapped)).isNotNull();
        assertThat(VerifyCommand.findBudget(new IllegalStateException("boom"))).isNull();
    }
}
