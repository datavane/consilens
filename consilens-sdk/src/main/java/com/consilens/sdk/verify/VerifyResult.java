package com.consilens.sdk.verify;

import com.consilens.core.diff.DiffResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyResult {

    private Verdict verdict;
    private int exitCode;
    private DiffResult.DiffStatistics summary;
    private DeltaSummary delta;

    @Builder.Default
    private List<PolicyCheck> checks = Collections.emptyList();

    /** 容差内（未计入差异）的行样本；报告里单列，不参与 verdict 判定。 */
    @Builder.Default
    private List<com.consilens.core.diff.DiffRow> withinToleranceDifferences = Collections.emptyList();

    /** Populated when the verdict is FAIL/INCONCLUSIVE to explain the outcome. */
    private String message;
}
