package com.consilens.sdk;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;
import com.consilens.sdk.verify.Verdict;
import com.consilens.sdk.verify.VerifyPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the public SDK surface. Update this test only together with a
 * deliberate API version bump.
 */
class SdkApiCompatibilityTest {

    @Test
    void engineContractShouldStayStable() throws Exception {
        assertThat(ConsilensEngine.class.getMethod("info").getReturnType())
                .isEqualTo(EngineInfo.class);
        assertThat(ConsilensEngine.class.getMethod("execute", CompareRequest.class)
                .getReturnType()).isEqualTo(DiffResult.class);
        assertThat(ConsilensEngine.class.getMethod("execute", CompareRequest.class, RunOptions.class)
                .getReturnType()).isEqualTo(DiffResult.class);
        assertThat(ConsilensEngine.class.getMethod("verify", CompareRequest.class,
                VerifyPolicy.class, RunOptions.class)).isNotNull();
        assertThat(ConsilensEngine.class.getMethod("close").getReturnType())
                .isEqualTo(void.class);
        assertThat(EngineInfo.API_VERSION).isEqualTo("1.0");
    }

    @Test
    void runOptionsContractShouldStayStable() throws Exception {
        assertThat(RunOptions.class.getMethod("defaults").getReturnType())
                .isEqualTo(RunOptions.class);
        assertThat(RunOptions.class.getMethod("builder").getReturnType())
                .isEqualTo(RunOptions.Builder.class);
        assertThat(RunOptions.class.getMethod("getMaxDifferences").getReturnType())
                .isEqualTo(Long.class);
        assertThat(RunOptions.class.getMethod("getMaxDurationMs").getReturnType())
                .isEqualTo(Long.class);
        assertThat(RunOptions.class.getMethod("getMaxRowsScanned").getReturnType())
                .isEqualTo(Long.class);
        assertThat(RunOptions.class.getMethod("getMaxBytesFetched").getReturnType())
                .isEqualTo(Long.class);
        assertThat(RunOptions.class.getMethod("getListener").getReturnType())
                .isEqualTo(RunListener.class);
        assertThat(RunListener.class.getMethod("onProgress",
                com.consilens.connector.api.planner.CompareProgress.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("maxDifferences", long.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("maxDurationMs", long.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("maxRowsScanned", long.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("maxBytesFetched", long.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("listener", RunListener.class)).isNotNull();
        assertThat(RunOptions.Builder.class.getMethod("build").getReturnType())
                .isEqualTo(RunOptions.class);
    }

    @Test
    void resultContractShouldKeepAdditiveFields() throws Exception {
        assertThat(DiffResult.class.getMethod("getDifferences")).isNotNull();
        assertThat(DiffResult.class.getMethod("getStatistics")).isNotNull();
        assertThat(DiffResult.class.getMethod("getColumnStats")).isNotNull();
        assertThat(DiffResult.class.getMethod("getSchemaCompatibility")).isNotNull();
        assertThat(DiffResult.class.getMethod("getFingerprint")).isNotNull();
        assertThat(DiffResult.class.getMethod("getEvidence")).isNotNull();
    }

    @Test
    void verdictExitCodesShouldMatchCliContract() {
        assertThat(Verdict.PASS.exitCode()).isZero();
        assertThat(Verdict.FAIL.exitCode()).isEqualTo(1);
        assertThat(Verdict.ERROR.exitCode()).isEqualTo(2);
        assertThat(Verdict.INCONCLUSIVE.exitCode()).isEqualTo(3);
        assertThat(Verdict.BUDGET_EXCEEDED.exitCode()).isEqualTo(4);
    }
}
