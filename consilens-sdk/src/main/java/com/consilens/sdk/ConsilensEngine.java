package com.consilens.sdk;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.diff.DiffResult;
import com.consilens.sdk.verify.VerifyPolicy;
import com.consilens.sdk.verify.VerifyResult;

/**
 * Embeddable entry point for Consilens.
 *
 * <p>This is a thin facade over the existing {@code CompareRuntime}. It does not
 * implement comparison algorithms; it only adds lifecycle, result post-processing
 * and a stable contract for platform integrators.
 */
public interface ConsilensEngine extends AutoCloseable {

    EngineInfo info();

    default DiffResult execute(CompareRequest request) {
        return execute(request, RunOptions.defaults());
    }

    DiffResult execute(CompareRequest request, RunOptions options);

    /**
     * Runs the comparison and evaluates a CI gate policy. The verdict is
     * deterministic: PASS/FAIL/INCONCLUSIVE per the policy, never inferred.
     */
    VerifyResult verify(CompareRequest request, VerifyPolicy policy, RunOptions options);

    @Override
    default void close() {
        // no-op: the underlying runtime owns no global resources
    }
}
